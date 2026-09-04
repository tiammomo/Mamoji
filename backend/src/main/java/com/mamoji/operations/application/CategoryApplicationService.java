package com.mamoji.operations.application;

import com.mamoji.platform.tenant.Company;
import com.mamoji.operations.api.CategoryCreateRequest;
import com.mamoji.operations.api.CategoryUpdateRequest;
import com.mamoji.operations.domain.Category;
import com.mamoji.platform.access.AccessContextService;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.audit.application.AuditTrailService;
import com.mamoji.service.OutboxEventService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Operations application boundary for company-scoped transaction category lifecycle. */
@Service
public class CategoryApplicationService {
    private final CategoryRepository repository;
    private final AccessContextService accessContext;
    private final AuditTrailService auditTrail;
    private final OutboxEventService outbox;

    public CategoryApplicationService(
        CategoryRepository repository,
        AccessContextService accessContext,
        AuditTrailService auditTrail,
        OutboxEventService outbox
    ) {
        this.repository = repository;
        this.accessContext = accessContext;
        this.auditTrail = auditTrail;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<Category> listCategories(ActorContext actor, String type, Long companyId) {
        Company company = accessContext.requireCompany(actor, companyId);
        String normalizedType = type == null ? null : type.trim().toLowerCase(Locale.ROOT);
        return repository.findAll(actor.userId(), company.id, normalizedType);
    }

    @Transactional
    public Category createCategory(ActorContext actor, CategoryCreateRequest request) {
        Company company = accessContext.requireCompany(actor, request.companyId());
        String now = OffsetDateTime.now().toString();
        Category category = new Category();
        category.userId = actor.userId();
        category.companyId = company.id;
        category.name = request.name().trim();
        category.icon = normalizedIcon(request.icon());
        category.color = normalizedColor(request.color());
        category.type = request.type().trim().toLowerCase(Locale.ROOT);
        category.status = 1;
        category.createdAt = now;
        category.updatedAt = now;
        repository.insert(category);
        audit(category, "create", "创建经营分类: " + category.name, actor, Map.of(
            "categoryName", category.name,
            "categoryType", category.type
        ));
        return category;
    }

    @Transactional
    public Category updateCategory(
        ActorContext actor,
        long id,
        Long companyId,
        CategoryUpdateRequest request
    ) {
        Category category = repository.findForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
        Long requestedCompanyId = companyId == null ? request.companyId() : companyId;
        Company company = accessContext.requireCompany(
            actor,
            requestedCompanyId == null ? category.companyId : requestedCompanyId
        );
        assertScopedOwner(category, actor.userId(), company.id);
        if (request.type() != null) {
            String requestedType = request.type().trim().toLowerCase(Locale.ROOT);
            if (!requestedType.equals(category.type) && repository.hasAccountingReferences(category.id)) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Referenced category type cannot be changed"
                );
            }
            category.type = requestedType;
        }
        if (request.name() != null) category.name = request.name().trim();
        if (request.icon() != null) category.icon = request.icon().trim();
        if (request.color() != null) category.color = request.color().trim().toLowerCase(Locale.ROOT);
        category.updatedAt = OffsetDateTime.now().toString();
        repository.update(category);
        audit(category, "update", "更新经营分类: " + category.name, actor, Map.of(
            "categoryName", category.name,
            "categoryType", category.type
        ));
        return category;
    }

    @Transactional
    public void deleteCategory(ActorContext actor, long id, Long companyId) {
        Category category = repository.findForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
        Company company = accessContext.requireCompany(actor, companyId == null ? category.companyId : companyId);
        assertScopedOwner(category, actor.userId(), company.id);
        if (repository.hasAccountingReferences(category.id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Category is used by transactions or budgets");
        }
        repository.delete(category);
        audit(category, "delete", "删除经营分类: " + category.name, actor, Map.of(
            "categoryName", category.name,
            "categoryType", category.type
        ));
    }

    private void assertScopedOwner(Category category, long userId, long companyId) {
        if (category.userId != userId || category.companyId != companyId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Category is outside the selected scope");
        }
    }

    private String normalizedIcon(String icon) {
        return icon == null ? "💡" : icon.trim();
    }

    private String normalizedColor(String color) {
        return color == null ? "#6366f1" : color.trim().toLowerCase(Locale.ROOT);
    }

    private void audit(
        Category category,
        String action,
        String summary,
        ActorContext actor,
        Map<String, Object> attributes
    ) {
        auditTrail.record(
            category.companyId,
            "category",
            category.id,
            action,
            summary,
            actor.userId(),
            actor.user().nickname
        );
        Map<String, Object> payload = new LinkedHashMap<>(attributes);
        payload.put("summary", summary);
        payload.put("action", action);
        outbox.publish(
            "operations.category." + action,
            category.companyId,
            "category",
            category.id,
            actor.userId(),
            payload
        );
    }
}
