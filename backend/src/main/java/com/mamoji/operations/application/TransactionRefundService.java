package com.mamoji.operations.application;

import com.mamoji.budget.application.BudgetApplicationService;
import com.mamoji.platform.tenant.Company;
import com.mamoji.finance.domain.Account;
import com.mamoji.operations.domain.Category;
import com.mamoji.operations.domain.RefundTransactionCommand;
import com.mamoji.operations.domain.TransactionRecord;
import com.mamoji.operations.domain.TransactionRiskAssessment;
import com.mamoji.operations.domain.TransactionRiskPolicy;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.identity.User;
import com.mamoji.platform.audit.application.AuditTrailService;
import com.mamoji.service.OutboxEventService;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

    private final TransactionWriteRepository transactions;
    private final TransactionQueryRepository transactionQueries;
    private final TransactionAccountingGateway accounting;
    private final AuditTrailService auditTrail;
    private final AccessControlService accessControl;
    private final OutboxEventService outboxEventService;
    private final BudgetApplicationService budgetService;

    public TransactionRefundService(
        TransactionWriteRepository transactions,
        TransactionQueryRepository transactionQueries,
        TransactionAccountingGateway accounting,
        AuditTrailService auditTrail,
        AccessControlService accessControl,
        OutboxEventService outboxEventService,
        BudgetApplicationService budgetService
    ) {
        this.transactions = transactions;
        this.transactionQueries = transactionQueries;
        this.accounting = accounting;
        this.auditTrail = auditTrail;
        this.accessControl = accessControl;
        this.outboxEventService = outboxEventService;
        this.budgetService = budgetService;
    }

    @Transactional(readOnly = true)
    public List<TransactionRecord> refundable(ActorContext actor, Long companyId) {
        User user = accessControl.requireUser(actor.legacyAuthorization());
        Company company = accessControl.resolveCompany(user, companyId);
        return transactionQueries.findAll(user.id, company.id).stream()
            .filter(transaction -> transaction.type == 2 && transaction.isRefundable)
            .sorted(TRANSACTION_ORDER)
            .toList();
    }

    @Transactional
    public Map<String, Object> refund(ActorContext actor, long id, RefundTransactionCommand command) {
        User user = accessControl.requireUser(actor.legacyAuthorization());
        validateCommand(command);
        TransactionRecord original = transactions.findForUpdate(id)
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
        Account account = accounting.findAccountForUpdate(original.accountId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Transaction account no longer exists"
            ));
        Category category = validateRelations(user, company.id, original, account);

        TransactionRecord refund = newRefund(user, company, original, command, refundDate, category, account);
        refund.originalTransactionId = original.id;
        refund.isRefundable = false;
        refund.budgetId = original.budgetId;
        transactions.insert(refund);

        TransactionRecord updatedOriginal = copyTransaction(original);
        updatedOriginal.refundedAmount = updatedOriginal.refundedAmount.add(command.amount());
        updatedOriginal.isRefundable = updatedOriginal.refundedAmount.compareTo(updatedOriginal.amount) < 0;
        updatedOriginal.updatedAt = OffsetDateTime.now().toString();
        transactions.update(updatedOriginal);
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

    private Category validateRelations(
        User user,
        long companyId,
        TransactionRecord original,
        Account account
    ) {
        assertScopedOwner(account.userId, account.companyId, user.id, companyId);
        Category category = accounting.findCategoryForUpdate(original.categoryId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid categoryId is required"));
        assertScopedOwner(category.userId, category.companyId, user.id, companyId);
        if (!"expense".equals(category.type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category type does not match transaction type");
        }
        return category;
    }

    private void saveAdjustedAccount(Account source, TransactionRecord refund) {
        Account adjusted = copyAccount(source);
        adjusted.balance = adjusted.balance.add(refund.amount);
        adjusted.availableBalance = nullToZero(adjusted.availableBalance).add(refund.amount);
        adjusted.riskLevel = accountRisk(adjusted);
        adjusted.updatedAt = OffsetDateTime.now().toString();
        accounting.updateAccount(adjusted);
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
            transactionQueries.findAll(transaction.userId, transaction.companyId)
        );
    }

    private TransactionRecord newRefund(
        User user,
        Company company,
        TransactionRecord original,
        RefundTransactionCommand command,
        LocalDate refundDate,
        Category category,
        Account account
    ) {
        TransactionRecord refund = new TransactionRecord();
        refund.userId = user.id;
        refund.companyId = company.id;
        refund.familyId = original.familyId;
        refund.type = 3;
        refund.amount = command.amount();
        refund.categoryId = original.categoryId;
        refund.categoryName = category.name;
        refund.categoryIcon = category.icon;
        refund.categoryColor = category.color;
        refund.accountId = original.accountId;
        refund.accountName = account.name;
        refund.date = refundDate.toString();
        refund.note = refundNote(command.note(), original.id);
        refund.refundedAmount = BigDecimal.ZERO;
        refund.createdAt = OffsetDateTime.now().toString();
        refund.updatedAt = refund.createdAt;
        return refund;
    }

    private void audit(long companyId, long refundId, long originalId, User user) {
        String summary = "退款交易 #" + originalId;
        auditTrail.record(
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
