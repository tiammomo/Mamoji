package com.mamoji.operations.application;

import com.mamoji.budget.application.BudgetApplicationService;
import com.mamoji.budget.domain.BudgetCapacity.BudgetCapacityExceededException;
import com.mamoji.budget.domain.BudgetReservation;
import com.mamoji.budget.domain.BudgetReservationCommand;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.domain.Models.User;
import com.mamoji.finance.domain.Account;
import com.mamoji.finance.domain.Ledger;
import com.mamoji.operations.api.TransactionCreateRequest;
import com.mamoji.operations.domain.Category;
import com.mamoji.operations.domain.CreateTransactionCommand;
import com.mamoji.operations.domain.TransactionRiskAssessment;
import com.mamoji.operations.domain.TransactionRiskPolicy;
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
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Typed application boundary for transaction writes.
 *
 * <p>All transaction creation entry points converge here so idempotency, balance,
 * budget, audit, and outbox changes share one transaction boundary.</p>
 */
@Service
public class TransactionApplicationService {
    private final TransactionWriteRepository transactions;
    private final TransactionQueryRepository transactionQueries;
    private final TransactionAccountingGateway accounting;
    private final EnterpriseStore enterpriseStore;
    private final AccessControlService accessControl;
    private final OutboxEventService outboxEventService;
    private final BudgetApplicationService budgetService;

    public TransactionApplicationService(
        TransactionWriteRepository transactions,
        TransactionQueryRepository transactionQueries,
        TransactionAccountingGateway accounting,
        EnterpriseStore enterpriseStore,
        AccessControlService accessControl,
        OutboxEventService outboxEventService,
        BudgetApplicationService budgetService
    ) {
        this.transactions = transactions;
        this.transactionQueries = transactionQueries;
        this.accounting = accounting;
        this.enterpriseStore = enterpriseStore;
        this.accessControl = accessControl;
        this.outboxEventService = outboxEventService;
        this.budgetService = budgetService;
    }

    @Transactional
    public Map<String, Object> create(
        ActorContext actor,
        TransactionCreateRequest request,
        String headerIdempotencyKey
    ) {
        String idempotencyKey = headerIdempotencyKey == null || headerIdempotencyKey.isBlank()
            ? request.idempotencyKey()
            : headerIdempotencyKey;
        CreateTransactionCommand command = new CreateTransactionCommand(
            request.companyId(),
            request.type() == null ? 2 : request.type(),
            request.amount(),
            request.categoryId(),
            request.accountId(),
            request.date(),
            request.note() == null ? "" : request.note(),
            idempotencyKey
        );
        return execute(actor.legacyAuthorization(), command);
    }

    @Transactional
    public Map<String, Object> create(String authorization, CreateTransactionCommand command) {
        return execute(authorization, command);
    }

    private Map<String, Object> execute(String authorization, CreateTransactionCommand command) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, command.companyId());
        validateCommand(command);
        LocalDate transactionDate = command.date() == null ? LocalDate.now() : command.date();
        String idempotencyKey = validIdempotencyKey(command.idempotencyKey());
        if (idempotencyKey != null) {
            transactions.lockIdempotency(company.id, idempotencyKey);
            Optional<TransactionRecord> replay = transactions.findByIdempotency(company.id, idempotencyKey);
            if (replay.isPresent()) {
                TransactionRecord existing = replay.get();
                requireMatchingReplay(existing, command, user.id);
                return Map.of(
                    "transaction", existing,
                    "risk", riskFor(existing),
                    "replayed", true
                );
            }
        }
        Account account = accounting.findAccountForUpdate(command.accountId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid accountId is required"));
        Category category = validateRelations(user, company.id, account, command);
        Long ledgerId = resolveLedgerId(user, company, account);
        Optional<BudgetReservation> reservation = reserveBudget(
            company.id,
            user.id,
            ledgerId,
            transactionDate,
            command,
            idempotencyKey
        );
        TransactionRecord transaction = newTransaction(
            user,
            company,
            ledgerId,
            transactionDate,
            command,
            idempotencyKey,
            category,
            account
        );
        transactions.insert(transaction);
        transaction.budgetId = reservation.map(BudgetReservation::budgetId).orElse(null);
        transactions.update(transaction);
        reservation.ifPresent(value -> budgetService.confirmReservation(value.id(), transaction.id));
        saveAdjustedAccount(account, transaction);
        budgetService.refreshCompany(company.id);
        audit(company.id, transaction, user);
        return Map.of("transaction", transaction, "risk", riskFor(transaction));
    }

    private Optional<BudgetReservation> reserveBudget(
        long companyId,
        long userId,
        Long ledgerId,
        LocalDate transactionDate,
        CreateTransactionCommand command,
        String idempotencyKey
    ) {
        if (command.type() != 2) {
            return Optional.empty();
        }
        String referenceKey = "transaction:" + (
            idempotencyKey == null ? UUID.randomUUID().toString() : idempotencyKey
        );
        try {
            return budgetService.reserveExpense(new BudgetReservationCommand(
                companyId,
                userId,
                ledgerId,
                command.categoryId(),
                transactionDate,
                command.amount(),
                referenceKey,
                null
            ));
        } catch (BudgetCapacityExceededException ex) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Budget capacity exceeded; available amount is " + ex.available().stripTrailingZeros().toPlainString()
            );
        }
    }

    private void requireMatchingReplay(
        TransactionRecord existing,
        CreateTransactionCommand command,
        long userId
    ) {
        String note = command.note() == null ? "" : command.note();
        boolean matches = existing.userId == userId
            && existing.type == command.type()
            && existing.amount.compareTo(command.amount()) == 0
            && existing.categoryId == command.categoryId()
            && existing.accountId == command.accountId()
            && (command.date() == null || existing.date.equals(command.date().toString()))
            && Objects.equals(existing.note, note);
        if (!matches) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Idempotency key was already used for another transaction"
            );
        }
    }

    private void validateCommand(CreateTransactionCommand command) {
        if (command.type() != 1 && command.type() != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be income(1) or expense(2)");
        }
        if (command.amount() == null || command.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be positive");
        }
        if (command.categoryId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "categoryId is required");
        }
        if (command.accountId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accountId is required");
        }
    }

    private Category validateRelations(
        User user,
        long companyId,
        Account account,
        CreateTransactionCommand command
    ) {
        Category category = accounting.findCategoryForUpdate(command.categoryId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid categoryId is required"));
        assertScopedOwner(account.userId, account.companyId, user.id, companyId);
        assertScopedOwner(category.userId, category.companyId, user.id, companyId);
        String expectedCategoryType = command.type() == 1 ? "income" : "expense";
        if (!expectedCategoryType.equals(category.type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category type does not match transaction type");
        }
        return category;
    }

    private Long resolveLedgerId(User user, Company company, Account account) {
        if (account.ledgerId != null) {
            Ledger ledger = accounting.findLedger(account.ledgerId).orElse(null);
            if (ledger == null || ledger.ownerId != user.id || !Objects.equals(ledger.companyId, company.id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Account ledger is outside the selected company");
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

    private void saveAdjustedAccount(Account source, TransactionRecord transaction) {
        Account account = copyAccount(source);
        BigDecimal delta = transaction.amount;
        if (transaction.type == 1) {
            account.balance = account.balance.add(delta);
            account.availableBalance = nullToZero(account.availableBalance).add(delta);
        } else {
            account.balance = account.balance.subtract(delta);
            account.availableBalance = nullToZero(account.availableBalance).subtract(delta);
        }
        account.riskLevel = accountRisk(account);
        account.updatedAt = OffsetDateTime.now().toString();
        accounting.updateAccount(account);
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

    private TransactionRecord newTransaction(
        User user,
        Company company,
        Long ledgerId,
        LocalDate transactionDate,
        CreateTransactionCommand command,
        String idempotencyKey,
        Category category,
        Account account
    ) {
        TransactionRecord transaction = new TransactionRecord();
        transaction.userId = user.id;
        transaction.companyId = company.id;
        transaction.familyId = ledgerId;
        transaction.type = command.type();
        transaction.amount = command.amount();
        transaction.categoryId = command.categoryId();
        transaction.categoryName = category.name;
        transaction.categoryIcon = category.icon;
        transaction.categoryColor = category.color;
        transaction.accountId = command.accountId();
        transaction.accountName = account.name;
        transaction.date = transactionDate.toString();
        transaction.note = command.note() == null ? "" : command.note();
        transaction.idempotencyKey = idempotencyKey;
        transaction.refundedAmount = BigDecimal.ZERO;
        transaction.isRefundable = command.type() == 2;
        transaction.createdAt = OffsetDateTime.now().toString();
        transaction.updatedAt = transaction.createdAt;
        return transaction;
    }

    private void audit(long companyId, TransactionRecord transaction, User user) {
        String summary = "创建交易: " + transaction.note;
        enterpriseStore.auditLog(
            companyId,
            "transaction",
            transaction.id,
            "create",
            summary,
            user.id,
            user.nickname
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        payload.put("actorName", user.nickname);
        payload.put("entityType", "transaction");
        payload.put("action", "create");
        outboxEventService.publish(
            "accounting.transaction.create",
            companyId,
            "transaction",
            transaction.id,
            user.id,
            payload
        );
    }

    private String validIdempotencyKey(String value) {
        if (value == null || value.isBlank()) return null;
        String key = value.trim();
        if (key.length() > 128 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid idempotency key");
        }
        return key;
    }

    private void assertScopedOwner(long ownerId, Long recordCompanyId, long userId, long companyId) {
        if (ownerId != userId || !Objects.equals(recordCompanyId, companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private Account copyAccount(Account source) {
        Account target = new Account();
        try {
            for (var field : source.getClass().getFields()) {
                field.set(target, field.get(source));
            }
            return target;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to copy account", exception);
        }
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
