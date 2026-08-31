package com.mamoji.operations.application;

import com.mamoji.budget.application.BudgetApplicationService;
import com.mamoji.budget.domain.BudgetCapacity.BudgetCapacityExceededException;
import com.mamoji.budget.domain.BudgetReservation;
import com.mamoji.budget.domain.BudgetReservationCommand;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.User;
import com.mamoji.finance.domain.Account;
import com.mamoji.finance.domain.Ledger;
import com.mamoji.operations.domain.Category;
import com.mamoji.operations.domain.TransactionRecord;
import com.mamoji.operations.domain.UpdateTransactionCommand;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.service.OutboxEventService;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Transactional application boundary for modifying and deleting accounting transactions. */
@Service
public class TransactionMutationService {
    private final TransactionWriteRepository transactions;
    private final TransactionAccountingGateway accounting;
    private final EnterpriseStore enterpriseStore;
    private final AccessControlService accessControl;
    private final OutboxEventService outboxEventService;
    private final BudgetApplicationService budgetService;

    public TransactionMutationService(
        TransactionWriteRepository transactions,
        TransactionAccountingGateway accounting,
        EnterpriseStore enterpriseStore,
        AccessControlService accessControl,
        OutboxEventService outboxEventService,
        BudgetApplicationService budgetService
    ) {
        this.transactions = transactions;
        this.accounting = accounting;
        this.enterpriseStore = enterpriseStore;
        this.accessControl = accessControl;
        this.outboxEventService = outboxEventService;
        this.budgetService = budgetService;
    }

    @Transactional
    public TransactionRecord update(ActorContext actor, long id, UpdateTransactionCommand command) {
        User user = accessControl.requireUser(actor.legacyAuthorization());
        validateCommand(command);
        ScopedTransaction scoped = requireScopedTransactionForUpdate(user, id, command.companyId());
        TransactionRecord current = scoped.transaction();
        Company company = scoped.company();
        if (current.type == 3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund transactions cannot be edited");
        }

        TransactionRecord updated = copyTransaction(current);
        if (command.amount() != null) updated.amount = command.amount();
        if (command.categoryId() != null) updated.categoryId = command.categoryId();
        if (command.accountId() != null) updated.accountId = command.accountId();
        if (command.date() != null) updated.date = command.date().toString();
        if (command.note() != null) updated.note = command.note();
        validateRefundedTransactionChanges(current, updated);

        Map<Long, Account> lockedAccounts = lockAccounts(current.accountId, updated.accountId);
        Account oldAccount = lockedAccounts.get(current.accountId);
        Account newAccount = lockedAccounts.get(updated.accountId);
        Category category = validateRelationOwnership(
            user,
            company.id,
            updated.accountId,
            updated.categoryId,
            newAccount,
            updated.type
        );
        updated.categoryName = category.name;
        updated.categoryIcon = category.icon;
        updated.categoryColor = category.color;
        updated.accountName = newAccount.name;
        updated.familyId = resolveLedgerId(user, company, newAccount);

        budgetService.releaseTransactionReservation(current.id, "transaction updated");
        Optional<BudgetReservation> reservation = reserveUpdatedExpense(company.id, user.id, updated);
        updated.budgetId = reservation.map(BudgetReservation::budgetId).orElse(null);
        updated.isRefundable = updated.type == 2 && updated.refundedAmount.compareTo(updated.amount) < 0;
        updated.updatedAt = OffsetDateTime.now().toString();
        transactions.update(updated);
        reservation.ifPresent(value -> budgetService.confirmReservation(value.id(), updated.id));

        if (oldAccount.id == newAccount.id) {
            Account adjusted = copyAccount(oldAccount);
            adjustAccount(adjusted, current, -1);
            adjustAccount(adjusted, updated, 1);
            accounting.updateAccount(adjusted);
        } else {
            saveAdjustedAccount(oldAccount, current, -1);
            saveAdjustedAccount(newAccount, updated, 1);
        }
        budgetService.refreshCompany(company.id);
        audit(company.id, updated.id, "update", "更新交易: " + updated.note, user);
        return updated;
    }

    private void validateCommand(UpdateTransactionCommand command) {
        if (command.companyId() != null && command.companyId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "companyId must be positive");
        }
        if (command.amount() != null && command.amount().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be positive");
        }
        if (command.categoryId() != null && command.categoryId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "categoryId must be positive");
        }
        if (command.accountId() != null && command.accountId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accountId must be positive");
        }
        if (command.note() != null && command.note().length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "note must not exceed 2000 characters");
        }
    }

    @Transactional
    public void delete(ActorContext actor, long id, Long companyId) {
        User user = accessControl.requireUser(actor.legacyAuthorization());
        ScopedTransaction scoped = requireScopedTransactionForUpdate(user, id, companyId);
        TransactionRecord transaction = scoped.transaction();
        Company company = scoped.company();
        if (transaction.type != 3 && transactions.hasRefunds(transaction.id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transaction has refunds and cannot be deleted");
        }

        TransactionRecord original = null;
        if (transaction.type == 3 && transaction.originalTransactionId != null) {
            original = transactions.findForUpdate(transaction.originalTransactionId)
                .orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Original transaction no longer exists"
                ));
        }
        Account account = accounting.findAccountForUpdate(transaction.accountId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Transaction account no longer exists"
            ));
        saveAdjustedAccount(account, transaction, -1);
        if (original != null) {
            TransactionRecord updatedOriginal = copyTransaction(original);
            updatedOriginal.refundedAmount = updatedOriginal.refundedAmount
                .subtract(transaction.amount)
                .max(BigDecimal.ZERO);
            updatedOriginal.isRefundable = updatedOriginal.refundedAmount.compareTo(updatedOriginal.amount) < 0;
            updatedOriginal.updatedAt = OffsetDateTime.now().toString();
            transactions.update(updatedOriginal);
        }
        budgetService.releaseTransactionReservation(transaction.id, "transaction deleted");
        transactions.delete(transaction);
        budgetService.refreshCompany(company.id);
        audit(company.id, transaction.id, "delete", "删除交易: " + transaction.note, user);
    }

    private ScopedTransaction requireScopedTransactionForUpdate(User user, long id, Long companyId) {
        TransactionRecord transaction = transactions.findForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
        Company company = accessControl.resolveCompany(
            user,
            companyId == null ? transaction.companyId : companyId
        );
        assertScopedOwner(transaction.userId, transaction.companyId, user.id, company.id);
        return new ScopedTransaction(transaction, company);
    }

    private void validateRefundedTransactionChanges(TransactionRecord current, TransactionRecord updated) {
        if (current.refundedAmount.compareTo(BigDecimal.ZERO) > 0
            && (updated.accountId != current.accountId
                || updated.categoryId != current.categoryId
                || !Objects.equals(updated.date, current.date))) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Account, category, and date cannot change after a transaction has refunds"
            );
        }
        if (updated.refundedAmount.compareTo(updated.amount) > 0) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Transaction amount cannot be lower than refunded amount"
            );
        }
    }

    private Map<Long, Account> lockAccounts(long firstId, long secondId) {
        long low = Math.min(firstId, secondId);
        long high = Math.max(firstId, secondId);
        Account first = accounting.findAccountForUpdate(low)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid accountId is required"));
        Map<Long, Account> result = new LinkedHashMap<>();
        result.put(first.id, first);
        if (high != low) {
            Account second = accounting.findAccountForUpdate(high)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid accountId is required"));
            result.put(second.id, second);
        }
        return result;
    }

    private Category validateRelationOwnership(
        User user,
        long companyId,
        long accountId,
        long categoryId,
        Account account,
        int transactionType
    ) {
        if (account == null || account.id != accountId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid accountId is required");
        }
        Category category = accounting.findCategoryForUpdate(categoryId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid categoryId is required"));
        assertScopedOwner(account.userId, account.companyId, user.id, companyId);
        assertScopedOwner(category.userId, category.companyId, user.id, companyId);
        String expectedCategoryType = transactionType == 1 ? "income" : "expense";
        if (!expectedCategoryType.equals(category.type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category type does not match transaction type");
        }
        return category;
    }

    private Long resolveLedgerId(User user, Company company, Account account) {
        if (account.ledgerId != null) {
            Ledger ledger = accounting.findLedger(account.ledgerId).orElse(null);
            if (ledger == null || ledger.ownerId != user.id || !Objects.equals(ledger.companyId, company.id)) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Account ledger is outside the selected company"
                );
            }
            return ledger.id;
        }
        return accounting.findLedgers(user.id, company.id).stream()
            .map(ledger -> ledger.id)
            .findFirst()
            .orElseGet(() -> accounting.ensureCompanyAccountingWorkspace(
                user.id,
                company.id,
                company.currency,
                company.name
            ).id);
    }

    private Optional<BudgetReservation> reserveUpdatedExpense(
        long companyId,
        long userId,
        TransactionRecord transaction
    ) {
        if (transaction.type != 2) {
            return Optional.empty();
        }
        try {
            return budgetService.reserveExpense(new BudgetReservationCommand(
                companyId,
                userId,
                transaction.familyId,
                transaction.categoryId,
                LocalDate.parse(transaction.date),
                transaction.amount,
                "transaction-update:" + transaction.id + ":" + (transaction.version + 1),
                transaction.id
            ));
        } catch (BudgetCapacityExceededException ex) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Budget capacity exceeded; available amount is "
                    + ex.available().stripTrailingZeros().toPlainString()
            );
        }
    }

    private void saveAdjustedAccount(Account source, TransactionRecord transaction, int direction) {
        Account adjusted = copyAccount(source);
        adjustAccount(adjusted, transaction, direction);
        accounting.updateAccount(adjusted);
    }

    private void adjustAccount(Account account, TransactionRecord transaction, int direction) {
        BigDecimal delta = transaction.amount.multiply(BigDecimal.valueOf(direction));
        if (transaction.type == 1 || transaction.type == 3) {
            account.balance = account.balance.add(delta);
            account.availableBalance = nullToZero(account.availableBalance).add(delta);
        } else if (transaction.type == 2) {
            account.balance = account.balance.subtract(delta);
            account.availableBalance = nullToZero(account.availableBalance).subtract(delta);
        }
        account.riskLevel = accountRisk(account);
        account.updatedAt = OffsetDateTime.now().toString();
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

    private void audit(long companyId, long transactionId, String action, String summary, User user) {
        enterpriseStore.auditLog(
            companyId,
            "transaction",
            transactionId,
            action,
            summary,
            user.id,
            user.nickname
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        payload.put("actorName", user.nickname);
        payload.put("entityType", "transaction");
        payload.put("action", action);
        outboxEventService.publish(
            "accounting.transaction." + action,
            companyId,
            "transaction",
            transactionId,
            user.id,
            payload
        );
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
            throw new IllegalStateException("Failed to copy transaction mutation model", ex);
        }
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record ScopedTransaction(TransactionRecord transaction, Company company) {
    }
}
