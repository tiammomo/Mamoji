package com.mamoji.service;

import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import com.mamoji.common.PayloadReader;
import com.mamoji.budget.application.BudgetApplicationService;
import com.mamoji.domain.Models.Account;
import com.mamoji.domain.Models.Category;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.Ledger;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountingService {
    private static final Comparator<TransactionRecord> TRANSACTION_ORDER =
        Comparator.comparing((TransactionRecord tx) -> tx.date).reversed().thenComparing(tx -> tx.id);

    private final InMemoryStore store;
    private final EnterpriseStore enterpriseStore;
    private final AccessControlService accessControl;
    private final OutboxEventService outboxEventService;
    private final BudgetApplicationService budgetService;

    public AccountingService(
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

    public List<Account> listAccounts(String authorization) {
        return listAccounts(authorization, null);
    }

    public List<Account> listAccounts(String authorization, Long companyId) {
        User user = requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        List<Account> accounts = store.queryAccounts(user.id, company.id).stream()
            .map(this::copyAccount)
            .toList();
        attachAccountMetrics(accounts, user.id, company.id);
        return accounts;
    }

    public Account getAccount(String authorization, long id) {
        return getAccount(authorization, id, null);
    }

    public Account getAccount(String authorization, long id, Long companyId) {
        User user = requireUser(authorization);
        Account account = copyAccount(store.findAccount(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")));
        Company company = accessControl.resolveCompany(user, companyId == null ? account.companyId : companyId);
        assertScopedOwner(account.userId, account.companyId, user.id, company.id);
        attachAccountMetrics(account);
        return account;
    }

    Account getAccountForUpdate(String authorization, long id, Long companyId) {
        User user = requireUser(authorization);
        Account account = store.accountForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? account.companyId : companyId);
        assertScopedOwner(account.userId, account.companyId, user.id, company.id);
        return account;
    }

    @Transactional
    public Account createAccount(String authorization, Map<String, Object> body) {
        User user = requireUser(authorization);
        Company company = resolveCompany(user, body);
        Account account = store.account(
            user.id,
            company.id,
            defaultLedgerId(user, company),
            textOr(body.get("name"), "新账户"),
            textOr(body.get("type"), "cash"),
            nullableText(body.get("subType")),
            nullableText(body.get("bank")),
            String.valueOf(number(body.get("balance"), BigDecimal.ZERO))
        );
        account.includeInNetWorth = bool(body.get("includeInNetWorth"), true);
        applyAccountFields(account, body);
        store.saveAccount(account);
        audit(company.id, "account", account.id, "create", "创建资金账户: " + account.name, user);
        attachAccountMetrics(account);
        return account;
    }

    @Transactional
    public Account updateAccount(String authorization, long id, Map<String, Object> body) {
        return updateAccount(authorization, id, null, body);
    }

    @Transactional
    public Account updateAccount(String authorization, long id, Long companyId, Map<String, Object> body) {
        User user = requireUser(authorization);
        Account existing = store.accountForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? existing.companyId : companyId);
        assertScopedOwner(existing.userId, existing.companyId, user.id, company.id);
        Account account = copyAccount(existing);
        if (body.containsKey("name")) {
            account.name = text(body.get("name"));
        }
        if (body.containsKey("type")) {
            account.type = text(body.get("type"));
        }
        if (body.containsKey("subType")) {
            account.subType = nullableText(body.get("subType"));
        }
        if (body.containsKey("bank")) {
            account.bank = nullableText(body.get("bank"));
        }
        if (body.containsKey("balance")) {
            account.balance = number(body.get("balance"), account.balance);
            if (!body.containsKey("availableBalance")) {
                account.availableBalance = nullToZero(existing.availableBalance).add(account.balance.subtract(existing.balance));
            }
        }
        if (body.containsKey("includeInNetWorth")) {
            account.includeInNetWorth = bool(body.get("includeInNetWorth"), account.includeInNetWorth);
        }
        applyAccountFields(account, body);
        touch(account);
        store.saveAccount(account);
        audit(account.companyId, "account", account.id, "update", "更新资金账户: " + account.name, user);
        attachAccountMetrics(account);
        return account;
    }

    @Transactional
    public void deleteAccount(String authorization, long id) {
        deleteAccount(authorization, id, null);
    }

    @Transactional
    public void deleteAccount(String authorization, long id, Long companyId) {
        User user = requireUser(authorization);
        Account account = store.accountForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? account.companyId : companyId);
        assertScopedOwner(account.userId, account.companyId, user.id, company.id);
        if (store.accountHasTransactions(account.id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account has transactions");
        }
        store.deleteAccount(id);
        audit(account.companyId, "account", account.id, "delete", "删除资金账户: " + account.name, user);
    }

    public Map<String, Object> accountSummary(String authorization) {
        return accountSummary(authorization, null);
    }

    public Map<String, Object> accountSummary(String authorization, Long companyId) {
        List<Account> accounts = listAccounts(authorization, companyId);
        BigDecimal liabilities = accounts.stream()
            .filter(account -> account.includeInNetWorth)
            .filter(account -> account.type.equals("debt") || account.type.equals("credit"))
            .map(account -> account.balance.abs())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal assets = accounts.stream()
            .filter(account -> account.includeInNetWorth)
            .filter(account -> !account.type.equals("debt") && !account.type.equals("credit"))
            .map(account -> account.balance.max(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal availableBalance = accounts.stream()
            .filter(account -> !account.type.equals("debt"))
            .map(account -> account.availableBalance)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal frozenAmount = accounts.stream().map(account -> account.frozenAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditLimit = accounts.stream()
            .filter(account -> account.type.equals("credit"))
            .map(account -> account.creditLimit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal currentMonthIncome = accounts.stream().map(account -> account.monthlyIncome).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal currentMonthExpense = accounts.stream().map(account -> account.monthlyExpense).reduce(BigDecimal.ZERO, BigDecimal::add);
        long activeAccountCount = accounts.stream().filter(account -> account.status == 1).count();
        long pendingReconciliationCount = accounts.stream()
            .filter(account -> !"reconciled".equals(account.reconciliationStatus))
            .count();
        long highRiskCount = accounts.stream()
            .filter(account -> account.riskLevel.equals("high") || account.riskLevel.equals("critical"))
            .count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalAssets", assets);
        summary.put("totalLiabilities", liabilities);
        summary.put("netWorth", assets.subtract(liabilities));
        summary.put("availableBalance", availableBalance);
        summary.put("frozenAmount", frozenAmount);
        summary.put("creditLimit", creditLimit);
        summary.put("currentMonthIncome", currentMonthIncome);
        summary.put("currentMonthExpense", currentMonthExpense);
        summary.put("accountCount", accounts.size());
        summary.put("activeAccountCount", activeAccountCount);
        summary.put("pendingReconciliationCount", pendingReconciliationCount);
        summary.put("highRiskCount", highRiskCount);
        return summary;
    }

    public List<Category> listCategories(String authorization, String type) {
        return listCategories(authorization, type, null);
    }

    public List<Category> listCategories(String authorization, String type, Long companyId) {
        User user = requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        return store.queryCategories(user.id, company.id, type);
    }

    public Category getCategory(String authorization, long id) {
        return getCategory(authorization, id, null);
    }

    public Category getCategory(String authorization, long id, Long companyId) {
        User user = requireUser(authorization);
        Category category = store.findCategory(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? category.companyId : companyId);
        assertScopedOwner(category.userId, category.companyId, user.id, company.id);
        return category;
    }

    @Transactional
    public Category createCategory(String authorization, Map<String, Object> body) {
        User user = requireUser(authorization);
        Company company = resolveCompany(user, body);
        return store.category(
            user.id,
            company.id,
            textOr(body.get("name"), "新分类"),
            textOr(body.get("icon"), "💡"),
            textOr(body.get("color"), "#6366f1"),
            textOr(body.get("type"), "expense")
        );
    }

    public Category updateCategory(String authorization, long id, Map<String, Object> body) {
        return updateCategory(authorization, id, null, body);
    }

    @Transactional
    public Category updateCategory(String authorization, long id, Long companyId, Map<String, Object> body) {
        User user = requireUser(authorization);
        Category existing = store.categoryForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? existing.companyId : companyId);
        assertScopedOwner(existing.userId, existing.companyId, user.id, company.id);
        Category category = copyCategory(existing);
        if (body.containsKey("name")) {
            category.name = text(body.get("name"));
        }
        if (body.containsKey("icon")) {
            category.icon = text(body.get("icon"));
        }
        if (body.containsKey("color")) {
            category.color = text(body.get("color"));
        }
        if (body.containsKey("type")) {
            category.type = text(body.get("type"));
        }
        touch(category);
        store.saveCategory(category);
        return category;
    }

    public void deleteCategory(String authorization, long id) {
        deleteCategory(authorization, id, null);
    }

    @Transactional
    public void deleteCategory(String authorization, long id, Long companyId) {
        User user = requireUser(authorization);
        Category category = store.categoryForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? category.companyId : companyId);
        assertScopedOwner(category.userId, category.companyId, user.id, company.id);
        if (store.categoryHasAccountingReferences(category.id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Category is used by transactions or budgets");
        }
        store.deleteCategory(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PagedResponse<TransactionRecord> listTransactions(String authorization, Map<String, String> params) {
        User user = requireUser(authorization);
        Company company = accessControl.resolveCompany(user, optionalLong(params.get("companyId")).orElse(null));
        return store.queryTransactions(user.id, company.id, params, PageRequest.from(params));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> transactionSummary(String authorization, Map<String, String> params) {
        User user = requireUser(authorization);
        Company company = accessControl.resolveCompany(user, optionalLong(params.get("companyId")).orElse(null));
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal refund = BigDecimal.ZERO;
        BigDecimal pendingCollection = BigDecimal.ZERO;
        BigDecimal customerRefund = BigDecimal.ZERO;
        BigDecimal severance = BigDecimal.ZERO;
        long rows = 0;
        long largeCount = 0;
        long reviewCount = 0;

        List<TransactionRecord> transactions = store.queryTransactionSummaryRows(user.id, company.id, params);
        for (TransactionRecord tx : transactions) {
            rows += 1;
            if (tx.type == 1) income = income.add(tx.amount);
            if (tx.type == 2) expense = expense.add(tx.amount);
            if (tx.type == 3) refund = refund.add(tx.amount);
            String searchable = transactionSearchText(tx);
            boolean pending = tx.type == 1 && containsAny(searchable, "待回款", "应收", "未回款", "尾款", "分期", "验收后", "交付后", "回款中");
            boolean customerRefundRow = tx.type == 2 && containsAny(searchable, "客户退款", "退款给客户", "收入退款", "订单退款", "项目退款", "退货退款", "服务退款");
            boolean severanceRow = tx.type == 2 && containsAny(searchable, "裁员", "离职补偿", "经济补偿", "遣散", "n+1", "n+ 1", "补偿金", "解除劳动");
            if (pending) pendingCollection = pendingCollection.add(tx.amount);
            if (customerRefundRow) customerRefund = customerRefund.add(tx.amount);
            if (severanceRow) severance = severance.add(tx.amount);
            boolean large = tx.amount.compareTo(new BigDecimal("10000")) >= 0;
            if (large) largeCount += 1;
            if (large || (tx.type == 2 && tx.isRefundable) || pending || customerRefundRow || severanceRow || text(tx.note).isBlank()) {
                reviewCount += 1;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("income", income);
        summary.put("expense", expense);
        summary.put("refund", refund);
        summary.put("pendingCollection", pendingCollection);
        summary.put("customerRefund", customerRefund);
        summary.put("severance", severance);
        summary.put("netCollectedIncome", income.subtract(customerRefund));
        summary.put("net", income.add(refund).subtract(expense));
        summary.put("rows", rows);
        summary.put("largeCount", largeCount);
        summary.put("reviewCount", reviewCount);
        return summary;
    }

    public TransactionRecord getTransaction(String authorization, long id) {
        return getTransaction(authorization, id, null);
    }

    public TransactionRecord getTransaction(String authorization, long id, Long companyId) {
        User user = requireUser(authorization);
        TransactionRecord tx = requireTransaction(user, id, companyId);
        store.attachTransactionRelations(tx);
        return tx;
    }

    @Transactional
    public Map<String, Object> createTransaction(String authorization, Map<String, Object> body) {
        User user = requireUser(authorization);
        Company company = resolveCompany(user, body);
        String idempotencyKey = idempotencyKey(body.get("idempotencyKey"));
        if (idempotencyKey != null) {
            store.lockTransactionIdempotency(company.id, idempotencyKey);
            Optional<TransactionRecord> replay = store.findTransactionByIdempotency(company.id, idempotencyKey);
            if (replay.isPresent()) {
                TransactionRecord existing = replay.get();
                store.attachTransactionRelations(existing);
                return Map.of("transaction", existing, "risk", riskFor(existing), "replayed", true);
            }
        }
        int type = intValue(body.get("type"), 2);
        if (type != 1 && type != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be income(1) or expense(2)");
        }
        BigDecimal amount = positiveAmount(body.get("amount"), "amount");
        long categoryId = requiredId(body.get("categoryId"), "categoryId");
        long accountId = requiredId(body.get("accountId"), "accountId");
        String date = validDate(textOr(body.get("date"), LocalDate.now().toString()), "date");
        Account lockedAccount = store.accountForUpdate(accountId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid accountId is required"));
        TransactionRelations relations = validateRelationOwnership(user, company.id, accountId, categoryId, lockedAccount, type);
        Long ledgerId = resolveLedgerId(user, company, relations.account());
        TransactionRecord tx = store.transaction(
            user.id,
            company.id,
            ledgerId,
            type,
            String.valueOf(amount),
            categoryId,
            accountId,
            date,
            textOr(body.get("note"), ""),
            idempotencyKey
        );
        tx.budgetId = budgetService.matchingBudgetId(tx).orElse(null);
        store.saveTransaction(tx);
        saveAdjustedAccount(relations.account(), tx, 1);
        budgetService.refreshCompany(company.id);
        audit(company.id, "transaction", tx.id, "create", "创建交易: " + tx.note, user);
        return Map.of("transaction", tx, "risk", riskFor(tx));
    }

    @Transactional
    public TransactionRecord updateTransaction(String authorization, long id, Map<String, Object> body) {
        User user = requireUser(authorization);
        TransactionRecord current = requireTransactionForUpdate(user, id, optionalLong(body.get("companyId")).orElse(null));
        if (current.type == 3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund transactions cannot be edited");
        }
        Company company = accessControl.resolveCompany(user, current.companyId);
        TransactionRecord tx = copyTransaction(current);
        if (body.containsKey("amount")) {
            tx.amount = positiveAmount(body.get("amount"), "amount");
        }
        if (body.containsKey("categoryId")) {
            tx.categoryId = longValue(body.get("categoryId"), tx.categoryId);
        }
        if (body.containsKey("accountId")) {
            tx.accountId = longValue(body.get("accountId"), tx.accountId);
        }
        if (body.containsKey("date")) {
            tx.date = validDate(text(body.get("date")), "date");
        }
        if (body.containsKey("note")) {
            tx.note = text(body.get("note"));
        }
        if (current.refundedAmount.compareTo(BigDecimal.ZERO) > 0
            && (tx.accountId != current.accountId
                || tx.categoryId != current.categoryId
                || !Objects.equals(tx.date, current.date))) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Account, category, and date cannot change after a transaction has refunds"
            );
        }
        if (tx.refundedAmount.compareTo(tx.amount) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transaction amount cannot be lower than refunded amount");
        }
        Map<Long, Account> lockedAccounts = lockAccounts(current.accountId, tx.accountId);
        Account oldAccount = lockedAccounts.get(current.accountId);
        Account newAccount = lockedAccounts.get(tx.accountId);
        validateRelationOwnership(user, company.id, tx.accountId, tx.categoryId, newAccount, tx.type);
        store.attachTransactionRelations(tx);
        tx.familyId = resolveLedgerId(user, company, newAccount);
        tx.budgetId = budgetService.matchingBudgetId(tx).orElse(null);
        tx.isRefundable = tx.type == 2 && tx.refundedAmount.compareTo(tx.amount) < 0;
        touch(tx);
        store.saveTransaction(tx);
        if (oldAccount.id == newAccount.id) {
            Account adjusted = copyAccount(oldAccount);
            adjustAccount(adjusted, current, -1);
            adjustAccount(adjusted, tx, 1);
            store.saveAccount(adjusted);
        } else {
            saveAdjustedAccount(oldAccount, current, -1);
            saveAdjustedAccount(newAccount, tx, 1);
        }
        budgetService.refreshCompany(company.id);
        audit(company.id, "transaction", tx.id, "update", "更新交易: " + tx.note, user);
        return tx;
    }

    @Transactional
    public void deleteTransaction(String authorization, long id) {
        deleteTransaction(authorization, id, null);
    }

    @Transactional
    public void deleteTransaction(String authorization, long id, Long companyId) {
        User user = requireUser(authorization);
        TransactionRecord tx = requireTransactionForUpdate(user, id, companyId);
        Company company = accessControl.resolveCompany(user, tx.companyId);
        if (tx.type != 3 && store.transactionHasRefunds(tx.id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transaction has refunds and cannot be deleted");
        }
        TransactionRecord original = null;
        if (tx.type == 3 && tx.originalTransactionId != null) {
            original = store.transactionForUpdate(tx.originalTransactionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Original transaction no longer exists"));
        }
        Account account = store.accountForUpdate(tx.accountId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Transaction account no longer exists"));
        saveAdjustedAccount(account, tx, -1);
        if (original != null) {
            TransactionRecord updatedOriginal = copyTransaction(original);
            updatedOriginal.refundedAmount = updatedOriginal.refundedAmount.subtract(tx.amount).max(BigDecimal.ZERO);
            updatedOriginal.isRefundable = updatedOriginal.refundedAmount.compareTo(updatedOriginal.amount) < 0;
            touch(updatedOriginal);
            store.saveTransaction(updatedOriginal);
        }
        store.deleteTransaction(id);
        budgetService.refreshCompany(company.id);
        audit(company.id, "transaction", tx.id, "delete", "删除交易: " + tx.note, user);
    }

    public List<TransactionRecord> refundableTransactions(String authorization) {
        return refundableTransactions(authorization, null);
    }

    public List<TransactionRecord> refundableTransactions(String authorization, Long companyId) {
        User user = requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        return store.queryAllTransactions(user.id, company.id).stream()
            .filter(tx -> tx.type == 2 && tx.isRefundable)
            .sorted(TRANSACTION_ORDER)
            .toList();
    }

    @Transactional
    public Map<String, Object> refundTransaction(String authorization, long id, Map<String, Object> body) {
        User user = requireUser(authorization);
        TransactionRecord original = requireTransactionForUpdate(user, id, optionalLong(body.get("companyId")).orElse(null));
        Company company = accessControl.resolveCompany(user, original.companyId);
        if (original.type != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only expense transactions can be refunded");
        }
        BigDecimal refundAmount = positiveAmount(body.get("amount"), "amount");
        BigDecimal remaining = original.amount.subtract(original.refundedAmount);
        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0 || refundAmount.compareTo(remaining) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund amount exceeds remaining refundable amount");
        }
        String refundDate = validDate(textOr(body.get("date"), LocalDate.now().toString()), "date");
        Account account = store.accountForUpdate(original.accountId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Transaction account no longer exists"));
        validateRelationOwnership(user, company.id, original.accountId, original.categoryId, account, original.type);
        TransactionRecord refund = store.transaction(
            user.id,
            company.id,
            original.familyId,
            3,
            String.valueOf(refundAmount),
            original.categoryId,
            original.accountId,
            refundDate,
            textOr(body.get("note"), "Refund for #" + original.id)
        );
        refund.originalTransactionId = original.id;
        refund.isRefundable = false;
        refund.budgetId = original.budgetId;
        TransactionRecord updatedOriginal = copyTransaction(original);
        updatedOriginal.refundedAmount = updatedOriginal.refundedAmount.add(refundAmount);
        updatedOriginal.isRefundable = updatedOriginal.refundedAmount.compareTo(updatedOriginal.amount) < 0;
        touch(updatedOriginal);
        store.saveTransaction(refund);
        store.saveTransaction(updatedOriginal);
        saveAdjustedAccount(account, refund, 1);
        budgetService.refreshCompany(company.id);
        audit(company.id, "transaction", refund.id, "refund", "退款交易 #" + original.id, user);
        return Map.of("transaction", refund, "risk", riskFor(refund));
    }

    private void applyAccountFields(Account account, Map<String, Object> body) {
        if (body.containsKey("accountNo")) {
            account.accountNo = nullableText(body.get("accountNo"));
        }
        if (body.containsKey("openingBank")) {
            account.openingBank = nullableText(body.get("openingBank"));
        }
        if (body.containsKey("currency")) {
            account.currency = textOr(body.get("currency"), "CNY");
        } else if (account.currency == null || account.currency.isBlank()) {
            account.currency = "CNY";
        }
        if (body.containsKey("availableBalance")) {
            account.availableBalance = number(body.get("availableBalance"), account.availableBalance);
        } else if (account.availableBalance == null) {
            account.availableBalance = account.balance.subtract(nullToZero(account.frozenAmount));
        }
        if (body.containsKey("creditLimit")) {
            account.creditLimit = number(body.get("creditLimit"), account.creditLimit);
        } else if (account.creditLimit == null) {
            account.creditLimit = BigDecimal.ZERO;
        }
        if (body.containsKey("frozenAmount")) {
            account.frozenAmount = number(body.get("frozenAmount"), account.frozenAmount);
        } else if (account.frozenAmount == null) {
            account.frozenAmount = BigDecimal.ZERO;
        }
        if (body.containsKey("openedAt")) {
            account.openedAt = nullableText(body.get("openedAt"));
        }
        if (body.containsKey("lastReconciledAt")) {
            account.lastReconciledAt = nullableText(body.get("lastReconciledAt"));
        }
        if (body.containsKey("ownerName")) {
            account.ownerName = nullableText(body.get("ownerName"));
        } else if (account.ownerName == null || account.ownerName.isBlank()) {
            account.ownerName = "财务负责人";
        }
        if (body.containsKey("purpose")) {
            account.purpose = nullableText(body.get("purpose"));
        } else if (account.purpose == null || account.purpose.isBlank()) {
            account.purpose = accountPurpose(account.type);
        }
        if (body.containsKey("reconciliationStatus")) {
            account.reconciliationStatus = normalizeReconciliationStatus(text(body.get("reconciliationStatus")));
        } else if (account.reconciliationStatus == null || account.reconciliationStatus.isBlank()) {
            account.reconciliationStatus = "pending";
        }
        if (body.containsKey("status")) {
            account.status = intValue(body.get("status"), account.status);
        }
        account.riskLevel = accountRisk(account);
    }

    private void attachAccountMetrics(Account account) {
        YearMonth current = YearMonth.now();
        BigDecimal monthlyIncome = BigDecimal.ZERO;
        BigDecimal monthlyExpense = BigDecimal.ZERO;
        long transactionCount = 0;
        String lastTransactionDate = null;
        for (TransactionRecord tx : store.queryAllTransactions(account.userId, account.companyId)) {
            if (tx.accountId != account.id || tx.userId != account.userId || !Objects.equals(tx.companyId, account.companyId)) {
                continue;
            }
            transactionCount++;
            if (lastTransactionDate == null || tx.date.compareTo(lastTransactionDate) > 0) {
                lastTransactionDate = tx.date;
            }
            if (sameMonth(tx.date, current)) {
                if (tx.type == 1) {
                    monthlyIncome = monthlyIncome.add(tx.amount);
                } else if (tx.type == 2) {
                    monthlyExpense = monthlyExpense.add(tx.amount);
                } else if (tx.type == 3) {
                    monthlyExpense = monthlyExpense.subtract(tx.amount);
                }
            }
        }
        account.monthlyIncome = monthlyIncome;
        account.monthlyExpense = monthlyExpense.max(BigDecimal.ZERO);
        account.currentMonthNetFlow = monthlyIncome.subtract(account.monthlyExpense);
        account.transactionCount = transactionCount;
        account.lastTransactionDate = lastTransactionDate;
        if (account.availableBalance == null) {
            account.availableBalance = account.balance.subtract(nullToZero(account.frozenAmount));
        }
        if (account.creditLimit == null) {
            account.creditLimit = BigDecimal.ZERO;
        }
        if (account.frozenAmount == null) {
            account.frozenAmount = BigDecimal.ZERO;
        }
        account.riskLevel = accountRisk(account);
    }

    private void attachAccountMetrics(List<Account> accounts, long userId, long companyId) {
        Map<Long, AccountMetrics> metricsByAccount = new HashMap<>();
        YearMonth current = YearMonth.now();
        for (TransactionRecord tx : store.queryAllTransactions(userId, companyId)) {
            if (tx.userId != userId || !Objects.equals(tx.companyId, companyId)) {
                continue;
            }
            AccountMetrics metrics = metricsByAccount.computeIfAbsent(tx.accountId, ignored -> new AccountMetrics());
            metrics.transactionCount++;
            if (metrics.lastTransactionDate == null || tx.date.compareTo(metrics.lastTransactionDate) > 0) {
                metrics.lastTransactionDate = tx.date;
            }
            if (sameMonth(tx.date, current)) {
                if (tx.type == 1) {
                    metrics.monthlyIncome = metrics.monthlyIncome.add(tx.amount);
                } else if (tx.type == 2) {
                    metrics.monthlyExpense = metrics.monthlyExpense.add(tx.amount);
                } else if (tx.type == 3) {
                    metrics.monthlyExpense = metrics.monthlyExpense.subtract(tx.amount);
                }
            }
        }
        for (Account account : accounts) {
            AccountMetrics metrics = metricsByAccount.getOrDefault(account.id, new AccountMetrics());
            account.monthlyIncome = metrics.monthlyIncome;
            account.monthlyExpense = metrics.monthlyExpense.max(BigDecimal.ZERO);
            account.currentMonthNetFlow = account.monthlyIncome.subtract(account.monthlyExpense);
            account.transactionCount = metrics.transactionCount;
            account.lastTransactionDate = metrics.lastTransactionDate;
            if (account.availableBalance == null) {
                account.availableBalance = account.balance.subtract(nullToZero(account.frozenAmount));
            }
            if (account.creditLimit == null) {
                account.creditLimit = BigDecimal.ZERO;
            }
            if (account.frozenAmount == null) {
                account.frozenAmount = BigDecimal.ZERO;
            }
            account.riskLevel = accountRisk(account);
        }
    }

    private static final class AccountMetrics {
        private BigDecimal monthlyIncome = BigDecimal.ZERO;
        private BigDecimal monthlyExpense = BigDecimal.ZERO;
        private long transactionCount;
        private String lastTransactionDate;
    }

    private String accountRisk(Account account) {
        if (account.status == 0) {
            return "medium";
        }
        if ("credit".equals(account.type)
            && account.creditLimit.compareTo(BigDecimal.ZERO) > 0
            && account.balance.abs().compareTo(account.creditLimit.multiply(new BigDecimal("0.9"))) >= 0) {
            return "high";
        }
        if (account.availableBalance.compareTo(BigDecimal.ZERO) < 0 || "exception".equals(account.reconciliationStatus)) {
            return "high";
        }
        if (isReconciliationStale(account) || account.frozenAmount.compareTo(BigDecimal.ZERO) > 0 || "pending".equals(account.reconciliationStatus)) {
            return "medium";
        }
        return "low";
    }

    private boolean isReconciliationStale(Account account) {
        if (account.lastReconciledAt == null || account.lastReconciledAt.isBlank()) {
            return true;
        }
        try {
            return LocalDate.parse(account.lastReconciledAt).isBefore(LocalDate.now().minusDays(15));
        } catch (Exception ignored) {
            return true;
        }
    }

    private String normalizeReconciliationStatus(String value) {
        return switch (value) {
            case "reconciled", "pending", "exception" -> value;
            default -> "pending";
        };
    }

    private String accountPurpose(String type) {
        return switch (type) {
            case "cash" -> "零星备用金和小额报销";
            case "bank" -> "客户回款、供应商付款和税费缴纳";
            case "credit" -> "短期周转和线上订阅付款";
            case "digital" -> "线上支付和平台收款";
            case "investment" -> "闲置资金理财和收益管理";
            case "debt" -> "借款、垫资和负债管理";
            default -> "企业资金账户";
        };
    }

    private User requireUser(String authorization) {
        return accessControl.requireUser(authorization);
    }

    private TransactionRecord requireTransaction(User user, long id, Long companyId) {
        TransactionRecord tx = store.findTransaction(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? tx.companyId : companyId);
        assertScopedOwner(tx.userId, tx.companyId, user.id, company.id);
        return tx;
    }

    private TransactionRecord requireTransactionForUpdate(User user, long id, Long companyId) {
        TransactionRecord tx = store.transactionForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? tx.companyId : companyId);
        assertScopedOwner(tx.userId, tx.companyId, user.id, company.id);
        return tx;
    }

    private TransactionRelations validateRelationOwnership(
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
        Category category = store.categoryForUpdate(categoryId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid categoryId is required"));
        assertScopedOwner(account.userId, account.companyId, user.id, companyId);
        assertScopedOwner(category.userId, category.companyId, user.id, companyId);
        String expectedCategoryType = transactionType == 1 ? "income" : "expense";
        if (!expectedCategoryType.equals(category.type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category type does not match transaction type");
        }
        return new TransactionRelations(account, category);
    }

    private Map<Long, Account> lockAccounts(long firstId, long secondId) {
        long low = Math.min(firstId, secondId);
        long high = Math.max(firstId, secondId);
        Account first = store.accountForUpdate(low)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid accountId is required"));
        Map<Long, Account> result = new LinkedHashMap<>();
        result.put(first.id, first);
        if (high != low) {
            Account second = store.accountForUpdate(high)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid accountId is required"));
            result.put(second.id, second);
        }
        return result;
    }

    private void saveAdjustedAccount(Account source, TransactionRecord tx, int direction) {
        Account adjusted = copyAccount(source);
        adjustAccount(adjusted, tx, direction);
        store.saveAccount(adjusted);
    }

    private void adjustAccount(Account account, TransactionRecord tx, int direction) {
        BigDecimal delta = tx.amount.multiply(BigDecimal.valueOf(direction));
        if (tx.type == 1 || tx.type == 3) {
            account.balance = account.balance.add(delta);
        } else if (tx.type == 2) {
            account.balance = account.balance.subtract(delta);
        }
        account.availableBalance = nullToZero(account.availableBalance);
        if (tx.type == 1 || tx.type == 3) {
            account.availableBalance = account.availableBalance.add(delta);
        } else if (tx.type == 2) {
            account.availableBalance = account.availableBalance.subtract(delta);
        }
        account.riskLevel = accountRisk(account);
        touch(account);
    }

    private Map<String, Object> riskFor(TransactionRecord tx) {
        YearMonth month = YearMonth.from(LocalDate.parse(tx.date));
        YearMonth previousMonth = month.minusMonths(1);
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal categoryCurrent = BigDecimal.ZERO;
        BigDecimal categoryLast = BigDecimal.ZERO;
        long dailyExpenseCount = 0;
        long duplicateCount = 0;
        List<TransactionRecord> riskTransactions = new ArrayList<>(store.queryAllTransactions(tx.userId, tx.companyId));
        riskTransactions.removeIf(item -> item.id == tx.id);
        riskTransactions.add(tx);
        for (TransactionRecord item : riskTransactions) {
            if (item.userId != tx.userId || !Objects.equals(item.companyId, tx.companyId)) {
                continue;
            }
            boolean currentMonth = sameMonth(item.date, month);
            if (currentMonth && item.type == 1) {
                income = income.add(item.amount);
            }
            if (currentMonth && item.type == 2) {
                expense = expense.add(item.amount);
            } else if (currentMonth && item.type == 3) {
                expense = expense.subtract(item.amount);
            }
            if (item.type == 2 && item.date.equals(tx.date)) {
                dailyExpenseCount++;
            }
            if (item.id != tx.id
                && item.type == tx.type
                && item.categoryId == tx.categoryId
                && item.accountId == tx.accountId
                && item.amount.compareTo(tx.amount) == 0
                && item.date.equals(tx.date)) {
                duplicateCount++;
            }
            if (item.categoryId == tx.categoryId && item.type == 2 && currentMonth) {
                categoryCurrent = categoryCurrent.add(item.amount);
            } else if (item.categoryId == tx.categoryId && item.type == 3 && currentMonth) {
                categoryCurrent = categoryCurrent.subtract(item.amount);
            } else if (item.categoryId == tx.categoryId && item.type == 2 && sameMonth(item.date, previousMonth)) {
                categoryLast = categoryLast.add(item.amount);
            } else if (item.categoryId == tx.categoryId && item.type == 3 && sameMonth(item.date, previousMonth)) {
                categoryLast = categoryLast.subtract(item.amount);
            }
        }
        expense = expense.max(BigDecimal.ZERO);
        categoryCurrent = categoryCurrent.max(BigDecimal.ZERO);
        categoryLast = categoryLast.max(BigDecimal.ZERO);
        List<String> flags = new ArrayList<>();
        String level = "low";
        if (tx.amount.compareTo(new BigDecimal("5000")) >= 0 && tx.type == 2) {
            flags.add("large_transaction");
            level = "high";
        }
        if (duplicateCount > 0) {
            flags.add("duplicate_candidate");
            level = level.equals("high") ? "high" : "medium";
        }
        double ratio = income.compareTo(BigDecimal.ZERO) == 0 ? 0 : expense.divide(income, 4, RoundingMode.HALF_UP).doubleValue();
        if (ratio > 0.8) {
            flags.add("expense_income_ratio_high");
            level = "high";
        }
        String message = flags.isEmpty() ? "交易风险较低" : "交易触发了风控提示";
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("level", level);
        risk.put("flags", flags);
        risk.put("message", message);
        risk.put("monthlyIncome", income);
        risk.put("monthlyExpense", expense);
        risk.put("expenseIncomeRatio", ratio);
        risk.put("dailyExpenseCount", dailyExpenseCount);
        risk.put("duplicateCount", duplicateCount);
        risk.put("categoryCurrentMonth", categoryCurrent);
        risk.put("categoryLastMonth", categoryLast);
        return risk;
    }

    private String transactionSearchText(TransactionRecord tx) {
        return (text(tx.note) + " " + text(tx.categoryName) + " " + text(tx.accountName)).toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private long defaultLedgerId(User user, Company company) {
        return store.queryLedgers(user.id, company.id).stream()
            .map(ledger -> ledger.id)
            .findFirst()
            .orElseGet(() -> store.ensureCompanyAccountingWorkspace(user.id, company.id, company.currency, company.name).id);
    }

    private Long resolveLedgerId(User user, Company company, Account account) {
        if (account.ledgerId != null) {
            Ledger ledger = store.findLedger(account.ledgerId).orElse(null);
            if (ledger == null || ledger.ownerId != user.id || !Objects.equals(ledger.companyId, company.id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Account ledger is outside the selected company");
            }
            return ledger.id;
        }
        return defaultLedgerId(user, company);
    }

    private Company resolveCompany(User user, Map<String, ?> values) {
        return accessControl.resolveCompany(user, optionalLong(values.get("companyId")).orElse(null));
    }

    private void assertScopedOwner(long ownerId, Long recordCompanyId, long currentUserId, long companyId) {
        if (ownerId != currentUserId || !Objects.equals(recordCompanyId, companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private BigDecimal positiveAmount(Object value, String field) {
        final BigDecimal amount;
        try {
            amount = number(value, BigDecimal.ZERO);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a number");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be positive");
        }
        return amount;
    }

    private String idempotencyKey(Object value) {
        String key = nullableText(value);
        if (key == null) return null;
        key = key.trim();
        if (key.isEmpty()) return null;
        if (key.length() > 128 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid idempotency key");
        }
        return key;
    }

    private long requiredId(Object value, String field) {
        final long id;
        try {
            id = longValue(value, 0);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a valid id");
        }
        if (id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return id;
    }

    private String validDate(String value, String field) {
        try {
            return LocalDate.parse(value).toString();
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must use yyyy-MM-dd format");
        }
    }

    private Account copyAccount(Account source) {
        return copyModel(source, new Account());
    }

    private Category copyCategory(Category source) {
        return copyModel(source, new Category());
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
            throw new IllegalStateException("Failed to copy accounting model", ex);
        }
    }

    private <T> T require(T value, String message) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        }
        return value;
    }

    private void touch(Object model) {
        try {
            model.getClass().getField("updatedAt").set(model, InMemoryStore.now());
        } catch (ReflectiveOperationException ignored) {
            // Models without updatedAt do not need mutation timestamps.
        }
    }

    private void audit(long companyId, String entityType, long entityId, String action, String summary, User user) {
        enterpriseStore.auditLog(companyId, entityType, entityId, action, summary, user.id, user.nickname);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        payload.put("actorName", user.nickname);
        payload.put("entityType", entityType);
        payload.put("action", action);
        outboxEventService.publish(
            "accounting." + entityType + "." + action,
            companyId,
            entityType,
            entityId,
            user.id,
            payload
        );
    }

    private static boolean sameMonth(String date, YearMonth month) {
        return YearMonth.from(LocalDate.parse(date)).equals(month);
    }

    private static String text(Object value) {
        return PayloadReader.text(value);
    }

    private static String textOr(Object value, String fallback) {
        return PayloadReader.textOr(value, fallback);
    }

    private static String nullableText(Object value) {
        return PayloadReader.nullableText(value);
    }

    private static BigDecimal number(Object value, BigDecimal fallback) {
        return PayloadReader.number(value, fallback);
    }

    private static Optional<Long> optionalLong(Object value) {
        return PayloadReader.optionalLong(value);
    }

    private static long longValue(Object value, long fallback) {
        return PayloadReader.longValue(value, fallback);
    }

    private static int intValue(Object value, int fallback) {
        return PayloadReader.intValue(value, fallback);
    }

    private static boolean bool(Object value, boolean fallback) {
        return PayloadReader.bool(value, fallback);
    }

    private static long longParam(Map<String, String> params, String key, long fallback) {
        return PayloadReader.longParam(params, key, fallback);
    }

    private static BigDecimal decimalParam(Map<String, String> params, String key, BigDecimal fallback) {
        return PayloadReader.decimalParam(params, key, fallback);
    }

    private record TransactionRelations(Account account, Category category) {
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

}
