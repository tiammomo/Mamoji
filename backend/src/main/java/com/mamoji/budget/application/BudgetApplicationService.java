package com.mamoji.budget.application;

import com.mamoji.budget.api.BudgetCreateRequest;
import com.mamoji.budget.api.BudgetUpdateRequest;
import com.mamoji.budget.domain.BudgetPolicy;
import com.mamoji.budget.infrastructure.BudgetRepository;
import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import com.mamoji.domain.Models.Budget;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.platform.access.AccessContextService;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.OutboxEventService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BudgetApplicationService {
    private final BudgetRepository repository;
    private final BudgetPolicy policy;
    private final AccessContextService accessContext;
    private final InMemoryStore compatibilityStore;
    private final EnterpriseStore auditStore;
    private final OutboxEventService outbox;

    public BudgetApplicationService(
        BudgetRepository repository,
        BudgetPolicy policy,
        AccessContextService accessContext,
        InMemoryStore compatibilityStore,
        EnterpriseStore auditStore,
        OutboxEventService outbox
    ) {
        this.repository = repository;
        this.policy = policy;
        this.accessContext = accessContext;
        this.compatibilityStore = compatibilityStore;
        this.auditStore = auditStore;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PagedResponse<Budget> list(
        ActorContext actor,
        Long companyId,
        Integer status,
        LocalDate startDate,
        LocalDate endDate,
        String keyword,
        int page,
        int size
    ) {
        Company company = readableCompany(actor, companyId);
        boolean companyWide = companyWideScope(accessContext.resolve(actor, company.id).scope());
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<Budget> budgets = repository.findByCompany(company.id).stream()
            .filter(budget -> companyWide || budget.userId == actor.userId())
            .filter(budget -> status == null || budget.status == status)
            .filter(budget -> startDate == null || !LocalDate.parse(budget.endDate).isBefore(startDate))
            .filter(budget -> endDate == null || !LocalDate.parse(budget.startDate).isAfter(endDate))
            .filter(budget -> normalizedKeyword.isBlank()
                || budget.name.toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                || (budget.categoryName != null && budget.categoryName.toLowerCase(Locale.ROOT).contains(normalizedKeyword)))
            .toList();
        return PagedResponse.of(budgets, new PageRequest(page, size));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Budget> active(ActorContext actor, Long companyId) {
        Company company = readableCompany(actor, companyId);
        boolean companyWide = companyWideScope(accessContext.resolve(actor, company.id).scope());
        return repository.findByCompany(company.id).stream()
            .filter(budget -> companyWide || budget.userId == actor.userId())
            .filter(budget -> budget.status == 1)
            .toList();
    }

    @Transactional(readOnly = true)
    public Budget get(ActorContext actor, long id, Long companyId) {
        Company company = readableCompany(actor, companyId);
        boolean companyWide = companyWideScope(accessContext.resolve(actor, company.id).scope());
        return repository.findById(company.id, id)
            .filter(budget -> companyWide || budget.userId == actor.userId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget not found"));
    }

    @Transactional
    public Budget create(ActorContext actor, BudgetCreateRequest request) {
        Company company = writableCompany(actor, request.companyId());
        validatePeriod(request.startDate(), request.endDate());
        validateCategory(company.id, request.categoryId());
        String now = InMemoryStore.now();
        Budget budget = new Budget();
        budget.companyId = company.id;
        budget.userId = actor.userId();
        budget.ledgerId = null;
        budget.categoryId = request.categoryId();
        budget.name = request.name().trim();
        budget.amount = request.amount();
        budget.startDate = request.startDate().toString();
        budget.endDate = request.endDate().toString();
        budget.warningThreshold = request.warningThreshold() == null ? 85 : request.warningThreshold();
        budget.status = 1;
        budget.spent = BigDecimal.ZERO;
        budget.createdAt = now;
        budget.updatedAt = now;
        policy.apply(budget);
        repository.insert(budget);
        synchronizeCompatibility(budget);
        audit(company.id, budget, "create", "创建经营预算: " + budget.name, actor);
        return repository.findById(company.id, budget.id).orElse(budget);
    }

    @Transactional
    public Budget update(ActorContext actor, long id, Long companyId, BudgetUpdateRequest request) {
        Budget budget = repository.findByIdForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget not found"));
        Company company = writableCompany(actor, companyId == null ? budget.companyId : companyId);
        if (budget.companyId == null || company.id != budget.companyId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Budget belongs to another company");
        }
        requireBudgetOwnerOrCompanyScope(actor, company.id, budget);
        if (request.version() != budget.version) {
            throw new OptimisticLockingFailureException("Budget was changed by another request: " + budget.id);
        }
        if (request.name() != null) budget.name = request.name().trim();
        if (request.amount() != null) budget.amount = request.amount();
        if (request.startDate() != null) budget.startDate = request.startDate().toString();
        if (request.endDate() != null) budget.endDate = request.endDate().toString();
        if (request.warningThreshold() != null) budget.warningThreshold = request.warningThreshold();
        if (Boolean.TRUE.equals(request.clearCategory())) {
            budget.categoryId = null;
        } else if (request.categoryId() != null) {
            budget.categoryId = request.categoryId();
        }
        if (request.status() != null) budget.status = request.status();
        validatePeriod(LocalDate.parse(budget.startDate), LocalDate.parse(budget.endDate));
        validateCategory(company.id, budget.categoryId);
        budget.updatedAt = InMemoryStore.now();
        repository.update(policy.apply(budget));
        refreshCompany(company.id);
        Budget updated = repository.findById(company.id, id).orElse(budget);
        synchronizeCompatibility(updated);
        audit(company.id, updated, "update", "更新经营预算: " + updated.name, actor);
        return updated;
    }

    @Transactional
    public void delete(ActorContext actor, long id, Long companyId) {
        Budget budget = repository.findByIdForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget not found"));
        Company company = writableCompany(actor, companyId == null ? budget.companyId : companyId);
        if (budget.companyId == null || company.id != budget.companyId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Budget belongs to another company");
        }
        requireBudgetOwnerOrCompanyScope(actor, company.id, budget);
        if (repository.hasTransactions(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Budget has transactions");
        }
        repository.delete(id);
        compatibilityStore.afterCommit(() -> compatibilityStore.budgets.remove(id));
        audit(company.id, budget, "delete", "删除经营预算: " + budget.name, actor);
    }

    public Optional<Long> matchingBudgetId(TransactionRecord transaction) {
        return repository.matchingBudgetId(transaction);
    }

    public void refreshCompany(long companyId) {
        List<Budget> budgets = repository.findByCompany(companyId);
        String now = InMemoryStore.now();
        budgets.forEach(budget -> {
            budget.updatedAt = now;
            repository.persistProjection(budget);
            synchronizeCompatibility(budget);
        });
    }

    public List<Budget> companyBudgets(long companyId) {
        return repository.findByCompany(companyId);
    }

    private Company readableCompany(ActorContext actor, Long companyId) {
        Company company = accessContext.requireCompany(actor, companyId);
        requireAnyPermission(actor, company.id, "budget.manage", "operations.read", "reports.read");
        return company;
    }

    private Company writableCompany(ActorContext actor, Long companyId) {
        Company company = accessContext.requireCompany(actor, companyId);
        accessContext.requirePermission(actor, company.id, "budget.manage");
        return company;
    }

    private void requireAnyPermission(ActorContext actor, long companyId, String... permissions) {
        var context = accessContext.resolve(actor, companyId);
        for (String permission : permissions) {
            if (context.permissions().contains(permission)) return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Budget read permission required");
    }

    private void requireBudgetOwnerOrCompanyScope(ActorContext actor, long companyId, Budget budget) {
        String scope = accessContext.resolve(actor, companyId).scope();
        if (!companyWideScope(scope) && budget.userId != actor.userId()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Budget is outside the actor data scope");
        }
    }

    private boolean companyWideScope(String scope) {
        return Set.of("group", "company", "company_set", "readonly").contains(scope);
    }

    private void validateCategory(long companyId, Long categoryId) {
        if (categoryId == null) return;
        var category = repository.category(categoryId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid categoryId is required"));
        if (category.companyId() == null || category.companyId() != companyId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Budget category belongs to another company");
        }
        if (!"expense".equals(category.type())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Budget category must be an expense category");
        }
    }

    private void validatePeriod(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endDate must not be before startDate");
        }
    }

    private void synchronizeCompatibility(Budget budget) {
        compatibilityStore.afterCommit(() -> compatibilityStore.budgets.put(budget.id, budget));
    }

    private void audit(long companyId, Budget budget, String action, String summary, ActorContext actor) {
        auditStore.auditLog(companyId, "budget", budget.id, action, summary, actor.userId(), actor.user().nickname);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        payload.put("budgetName", budget.name);
        payload.put("amount", budget.amount);
        payload.put("action", action);
        outbox.publish("budget." + action, companyId, "budget", budget.id, actor.userId(), payload);
    }
}
