package com.mamoji.operations.application;

import com.mamoji.budget.application.BudgetApplicationService;
import com.mamoji.domain.Models.Account;
import com.mamoji.domain.Models.Category;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.domain.Models.User;
import com.mamoji.operations.domain.RefundTransactionCommand;
import com.mamoji.operations.domain.TransactionRiskAssessment;
import com.mamoji.operations.domain.TransactionRiskPolicy;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.OutboxEventService;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Transactional application boundary for expense refunds. */
@Service
public class TransactionRefundService {
    private static final Comparator<TransactionRecord> TRANSACTION_ORDER = Comparator
        .comparing((TransactionRecord transaction) -> transaction.date)
        .reversed()
        .thenComparingLong(transaction -> transaction.id);

    private final InMemoryStore store;
    private final EnterpriseStore enterpriseStore;
    private final AccessControlService accessControl;
    private final OutboxEventService outboxEventService;
    private final BudgetApplicationService budgetService;

    public TransactionRefundService(
        InMemoryStore store,
        EnterpriseStore enterpriseStore,
        AccessControlService accessControl,
        OutboxEventService outboxEventService,
        BudgetApplicationService budgetService
    ) {
        this.store = store;
        this.enterpriseStore = enterpriseStore;
        this.accessControl = accessControl;
        this.outboxEventService = outboxEventService;
        this.budgetService = budgetService;
    }

    @Transactional(readOnly = true)
    public List<TransactionRecord> refundable(ActorContext actor, Long companyId) {
        User user = accessControl.requireUser(actor.legacyAuthorization());
        Company company = accessControl.resolveCompany(user, companyId);
        return store.queryAllTransactions(user.id, company.id).stream()
            .filter(transaction -> transaction.type == 2 && transaction.isRefundable)
            .sorted(TRANSACTION_ORDER)
            .toList();
    }

    @Transactional
    public Map<String, Object> refund(ActorContext actor, long id, RefundTransactionCommand command) {
        User user = accessControl.requireUser(actor.legacyAuthorization());
        validateCommand(command);
        TransactionRecord original = store.transactionForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
        Company company = accessControl.resolveCompany(
            user,
            command.companyId() == null ? original.companyId : command.companyId()
        );
        assertScopedOwner(original.userId, original.companyId, user.id, company.id);
        if (original.type != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only expense transactions can be refunded");
        }

        BigDecimal remaining = original.amount.subtract(original.refundedAmount);
        if (command.amount().compareTo(remaining) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund amount exceeds remaining refundable amount");
        }
        LocalDate refundDate = command.date() == null ? LocalDate.now() : command.date();
        if (refundDate.isBefore(LocalDate.parse(original.date))) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Refund date cannot be before original transaction date"
            );
        }
        Account account = store.accountForUpdate(original.accountId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Transaction account no longer exists"
            ));
        validateRelations(user, company.id, original, account);

        TransactionRecord refund = store.transaction(
            user.id,
            company.id,
            original.familyId,
            3,
            command.amount().toString(),
            original.categoryId,
            original.accountId,
            refundDate.toString(),
            refundNote(command.note(), original.id)
        );
        refund.originalTransactionId = original.id;
        refund.isRefundable = false;
        refund.budgetId = original.budgetId;

        TransactionRecord updatedOriginal = copyTransaction(original);
        updatedOriginal.refundedAmount = updatedOriginal.refundedAmount.add(command.amount());
        updatedOriginal.isRefundable = updatedOriginal.refundedAmount.compareTo(updatedOriginal.amount) < 0;
        updatedOriginal.updatedAt = InMemoryStore.now();
        store.saveTransaction(refund);
        store.saveTransaction(updatedOriginal);
        saveAdjustedAccount(account, refund);
        budgetService.refreshCompany(company.id);
        audit(company.id, refund.id, original.id, user);
        return Map.of("transaction", refund, "risk", riskFor(refund));
    }

    private void validateCommand(RefundTransactionCommand command) {
        if (command.companyId() != null && command.companyId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "companyId must be positive");
        }
        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be positive");
        }
        if (command.note() != null && command.note().length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "note must not exceed 2000 characters");
        }
    }

    private void validateRelations(User user, long companyId, TransactionRecord original, Account account) {
        assertScopedOwner(account.userId, account.companyId, user.id, companyId);
        Category category = store.categoryForUpdate(original.categoryId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid categoryId is required"));
        assertScopedOwner(category.userId, category.companyId, user.id, companyId);
        if (!"expense".equals(category.type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category type does not match transaction type");
        }
    }

    private void saveAdjustedAccount(Account source, TransactionRecord refund) {
        Account adjusted = copyAccount(source);
        adjusted.balance = adjusted.balance.add(refund.amount);
        adjusted.availableBalance = nullToZero(adjusted.availableBalance).add(refund.amount);
        adjusted.riskLevel = accountRisk(adjusted);
        adjusted.updatedAt = InMemoryStore.now();
        store.saveAccount(adjusted);
    }

    private String accountRisk(Account account) {
        if (account.status == 0) return "medium";
        if ("credit".equals(account.type)
            && nullToZero(account.creditLimit).compareTo(BigDecimal.ZERO) > 0
            && account.balance.abs().compareTo(account.creditLimit.multiply(new BigDecimal("0.9"))) >= 0) {
            return "high";
        }
        if (nullToZero(account.availableBalance).compareTo(BigDecimal.ZERO) < 0
            || "exception".equals(account.reconciliationStatus)) {
            return "high";
        }
        if (isReconciliationStale(account)
            || nullToZero(account.frozenAmount).compareTo(BigDecimal.ZERO) > 0
            || "pending".equals(account.reconciliationStatus)) {
            return "medium";
        }
        return "low";
    }

    private boolean isReconciliationStale(Account account) {
        if (account.lastReconciledAt == null || account.lastReconciledAt.isBlank()) return true;
        try {
            return LocalDate.parse(account.lastReconciledAt).isBefore(LocalDate.now().minusDays(15));
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private TransactionRiskAssessment riskFor(TransactionRecord transaction) {
        return TransactionRiskPolicy.assess(
            transaction,
            store.queryAllTransactions(transaction.userId, transaction.companyId)
        );
    }

    private void audit(long companyId, long refundId, long originalId, User user) {
        String summary = "退款交易 #" + originalId;
        enterpriseStore.auditLog(
            companyId,
            "transaction",
            refundId,
            "refund",
            summary,
            user.id,
            user.nickname
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        payload.put("actorName", user.nickname);
        payload.put("entityType", "transaction");
        payload.put("action", "refund");
        outboxEventService.publish(
            "accounting.transaction.refund",
            companyId,
            "transaction",
            refundId,
            user.id,
            payload
        );
    }

    private String refundNote(String note, long originalId) {
        return note == null || note.isBlank() ? "Refund for #" + originalId : note;
    }

    private void assertScopedOwner(long ownerId, Long recordCompanyId, long userId, long companyId) {
        if (ownerId != userId || !Objects.equals(recordCompanyId, companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private Account copyAccount(Account source) {
        return copyModel(source, new Account());
    }

    private TransactionRecord copyTransaction(TransactionRecord source) {
        return copyModel(source, new TransactionRecord());
    }

    private <T> T copyModel(T source, T target) {
        try {
            for (var field : source.getClass().getFields()) {
                field.set(target, field.get(source));
            }
            return target;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to copy transaction refund model", ex);
        }
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
