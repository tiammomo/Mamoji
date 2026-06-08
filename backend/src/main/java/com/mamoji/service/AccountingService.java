package com.mamoji.service;

import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import com.mamoji.common.PayloadReader;
import com.mamoji.domain.Models.Account;
import com.mamoji.domain.Models.Budget;
import com.mamoji.domain.Models.Category;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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

    public AccountingService(
        InMemoryStore store,
        EnterpriseStore enterpriseStore,
        AccessControlService accessControl,
        OutboxEventService outboxEventService
    ) {
        this.store = store;
        this.enterpriseStore = enterpriseStore;
        this.accessControl = accessControl;
        this.outboxEventService = outboxEventService;
    }

    public List<Account> listAccounts(String authorization) {
        User user = requireUser(authorization);
        return store.accounts.values().stream()
            .filter(account -> account.userId == user.id)
            .peek(this::attachAccountMetrics)
            .sorted(Comparator.comparing(account -> account.id))
            .toList();
    }

    public Account getAccount(String authorization, long id) {
        User user = requireUser(authorization);
        Account account = require(store.accounts.get(id), "Account not found");
        assertOwner(account.userId, user.id);
        attachAccountMetrics(account);
        return account;
    }

    @Transactional
    public Account createAccount(String authorization, Map<String, Object> body) {
        User user = requireUser(authorization);
        Account account = store.account(
            user.id,
            defaultLedgerId(user.id).orElse(null),
            textOr(body.get("name"), "新账户"),
            textOr(body.get("type"), "cash"),
            nullableText(body.get("subType")),
            nullableText(body.get("bank")),
            String.valueOf(number(body.get("balance"), BigDecimal.ZERO))
        );
        account.includeInNetWorth = bool(body.get("includeInNetWorth"), true);
        applyAccountFields(account, body);
        store.saveAccount(account);
        audit(0, "account", account.id, "create", "创建资金账户: " + account.name, user);
        attachAccountMetrics(account);
        return account;
    }

    @Transactional
    public Account updateAccount(String authorization, long id, Map<String, Object> body) {
        Account account = getAccount(authorization, id);
        User user = requireUser(authorization);
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
        }
        if (body.containsKey("includeInNetWorth")) {
            account.includeInNetWorth = bool(body.get("includeInNetWorth"), account.includeInNetWorth);
        }
        applyAccountFields(account, body);
        touch(account);
        store.saveAccount(account);
        audit(0, "account", account.id, "update", "更新资金账户: " + account.name, user);
        attachAccountMetrics(account);
        return account;
    }

    @Transactional
    public void deleteAccount(String authorization, long id) {
        Account account = getAccount(authorization, id);
        User user = requireUser(authorization);
        boolean used = store.transactions.values().stream().anyMatch(tx -> tx.accountId == account.id);
        if (used) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account has transactions");
        }
        store.deleteAccount(id);
        audit(0, "account", account.id, "delete", "删除资金账户: " + account.name, user);
    }

    public Map<String, Object> accountSummary(String authorization) {
        List<Account> accounts = listAccounts(authorization);
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
        User user = requireUser(authorization);
        return store.categories.values().stream()
            .filter(category -> category.userId == user.id)
            .filter(category -> type == null || type.isBlank() || category.type.equals(type))
            .sorted(Comparator.comparing(category -> category.id))
            .toList();
    }

    public Category getCategory(String authorization, long id) {
        User user = requireUser(authorization);
        Category category = require(store.categories.get(id), "Category not found");
        assertOwner(category.userId, user.id);
        return category;
    }

    public Category createCategory(String authorization, Map<String, Object> body) {
        User user = requireUser(authorization);
        return store.category(
            user.id,
            textOr(body.get("name"), "新分类"),
            textOr(body.get("icon"), "💡"),
            textOr(body.get("color"), "#6366f1"),
            textOr(body.get("type"), "expense")
        );
    }

    public Category updateCategory(String authorization, long id, Map<String, Object> body) {
        Category category = getCategory(authorization, id);
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
        Category category = getCategory(authorization, id);
        boolean used = store.transactions.values().stream().anyMatch(tx -> tx.categoryId == category.id);
        if (used) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Category has transactions");
        }
        store.deleteCategory(id);
    }

    public PagedResponse<Budget> listBudgets(String authorization, Map<String, String> params) {
        User user = requireUser(authorization);
        store.attachBudgetData();
        List<Budget> budgets = store.budgets.values().stream()
            .filter(budget -> budget.userId == user.id)
            .filter(filterBudget(params))
            .sorted(Comparator.comparing(budget -> budget.id))
            .toList();
        return PagedResponse.of(budgets, PageRequest.from(params));
    }

    public List<Budget> activeBudgets(String authorization) {
        return listBudgets(authorization, Map.of("status", "1", "size", "200")).content;
    }

    public Budget getBudget(String authorization, long id) {
        User user = requireUser(authorization);
        store.attachBudgetData();
        Budget budget = require(store.budgets.get(id), "Budget not found");
        assertOwner(budget.userId, user.id);
        return budget;
    }

    public Budget createBudget(String authorization, Map<String, Object> body) {
        User user = requireUser(authorization);
        Budget budget = store.budget(
            user.id,
            defaultLedgerId(user.id).orElse(null),
            optionalLong(body.get("categoryId")).orElse(null),
            textOr(body.get("name"), "预算"),
            String.valueOf(number(body.get("amount"), BigDecimal.ZERO)),
            textOr(body.get("startDate"), LocalDate.now().withDayOfMonth(1).toString()),
            textOr(body.get("endDate"), LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth()).toString()),
            intValue(body.get("warningThreshold"), 85)
        );
        store.refreshBudgetData();
        return budget;
    }

    public Budget updateBudget(String authorization, long id, Map<String, Object> body) {
        Budget budget = getBudget(authorization, id);
        if (body.containsKey("name")) {
            budget.name = text(body.get("name"));
        }
        if (body.containsKey("amount")) {
            budget.amount = number(body.get("amount"), budget.amount);
        }
        if (body.containsKey("startDate")) {
            budget.startDate = text(body.get("startDate"));
        }
        if (body.containsKey("endDate")) {
            budget.endDate = text(body.get("endDate"));
        }
        if (body.containsKey("warningThreshold")) {
            budget.warningThreshold = intValue(body.get("warningThreshold"), budget.warningThreshold);
        }
        if (body.containsKey("categoryId")) {
            budget.categoryId = optionalLong(body.get("categoryId")).orElse(null);
        }
        if (body.containsKey("status")) {
            budget.status = intValue(body.get("status"), budget.status);
        }
        touch(budget);
        store.saveBudget(budget);
        store.refreshBudgetData();
        return budget;
    }

    public void deleteBudget(String authorization, long id) {
        Budget budget = getBudget(authorization, id);
        store.deleteBudget(budget.id);
    }

    public PagedResponse<TransactionRecord> listTransactions(String authorization, Map<String, String> params) {
        User user = requireUser(authorization);
        List<TransactionRecord> txs = store.transactions.values().stream()
            .filter(tx -> tx.userId == user.id)
            .peek(store::attachTransactionRelations)
            .filter(filterTransaction(params))
            .sorted(TRANSACTION_ORDER)
            .toList();
        return PagedResponse.of(txs, PageRequest.from(params));
    }

    public TransactionRecord getTransaction(String authorization, long id) {
        User user = requireUser(authorization);
        TransactionRecord tx = requireTransaction(user, id);
        store.attachTransactionRelations(tx);
        return tx;
    }

    public Map<String, Object> createTransaction(String authorization, Map<String, Object> body) {
        User user = requireUser(authorization);
        TransactionRecord tx = store.transaction(
            user.id,
            defaultLedgerId(user.id).orElse(null),
            intValue(body.get("type"), 2),
            String.valueOf(number(body.get("amount"), BigDecimal.ZERO)),
            longValue(body.get("categoryId"), 0),
            longValue(body.get("accountId"), 0),
            textOr(body.get("date"), LocalDate.now().toString()),
            textOr(body.get("note"), "")
        );
        validateRelationOwnership(user, tx);
        tx.budgetId = matchingBudgetId(tx).orElse(null);
        store.saveTransaction(tx);
        applyToAccount(tx, 1);
        store.refreshBudgetData();
        return Map.of("transaction", tx, "risk", riskFor(tx));
    }

    public TransactionRecord updateTransaction(String authorization, long id, Map<String, Object> body) {
        User user = requireUser(authorization);
        TransactionRecord tx = requireTransaction(user, id);
        applyToAccount(tx, -1);
        if (body.containsKey("amount")) {
            tx.amount = number(body.get("amount"), tx.amount);
        }
        if (body.containsKey("categoryId")) {
            tx.categoryId = longValue(body.get("categoryId"), tx.categoryId);
        }
        if (body.containsKey("accountId")) {
            tx.accountId = longValue(body.get("accountId"), tx.accountId);
        }
        if (body.containsKey("date")) {
            tx.date = text(body.get("date"));
        }
        if (body.containsKey("note")) {
            tx.note = text(body.get("note"));
        }
        validateRelationOwnership(user, tx);
        store.attachTransactionRelations(tx);
        tx.budgetId = matchingBudgetId(tx).orElse(null);
        tx.isRefundable = tx.type == 2 && tx.refundedAmount.compareTo(tx.amount) < 0;
        touch(tx);
        store.saveTransaction(tx);
        applyToAccount(tx, 1);
        store.refreshBudgetData();
        return tx;
    }

    public void deleteTransaction(String authorization, long id) {
        User user = requireUser(authorization);
        TransactionRecord tx = requireTransaction(user, id);
        applyToAccount(tx, -1);
        store.deleteTransaction(id);
        store.refreshBudgetData();
    }

    public List<TransactionRecord> refundableTransactions(String authorization) {
        User user = requireUser(authorization);
        return store.transactions.values().stream()
            .filter(tx -> tx.userId == user.id)
            .filter(tx -> tx.type == 2 && tx.isRefundable)
            .peek(store::attachTransactionRelations)
            .sorted(TRANSACTION_ORDER)
            .toList();
    }

    public Map<String, Object> refundTransaction(String authorization, long id, Map<String, Object> body) {
        User user = requireUser(authorization);
        TransactionRecord original = requireTransaction(user, id);
        if (original.type != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only expense transactions can be refunded");
        }
        BigDecimal refundAmount = number(body.get("amount"), BigDecimal.ZERO);
        BigDecimal remaining = original.amount.subtract(original.refundedAmount);
        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0 || refundAmount.compareTo(remaining) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid refund amount");
        }
        TransactionRecord refund = store.transaction(
            user.id,
            original.familyId,
            3,
            String.valueOf(refundAmount),
            original.categoryId,
            original.accountId,
            textOr(body.get("date"), LocalDate.now().toString()),
            textOr(body.get("note"), "Refund for #" + original.id)
        );
        refund.originalTransactionId = original.id;
        refund.isRefundable = false;
        original.refundedAmount = original.refundedAmount.add(refundAmount);
        original.isRefundable = original.refundedAmount.compareTo(original.amount) < 0;
        touch(original);
        store.saveTransaction(refund);
        store.saveTransaction(original);
        applyToAccount(refund, 1);
        store.refreshBudgetData();
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
        for (TransactionRecord tx : store.transactions.values()) {
            if (tx.accountId != account.id || tx.userId != account.userId) {
                continue;
            }
            transactionCount++;
            if (lastTransactionDate == null || tx.date.compareTo(lastTransactionDate) > 0) {
                lastTransactionDate = tx.date;
            }
            if (sameMonth(tx.date, current)) {
                if (tx.type == 1 || tx.type == 3) {
                    monthlyIncome = monthlyIncome.add(tx.amount);
                } else if (tx.type == 2) {
                    monthlyExpense = monthlyExpense.add(tx.amount);
                }
            }
        }
        account.monthlyIncome = monthlyIncome;
        account.monthlyExpense = monthlyExpense;
        account.currentMonthNetFlow = monthlyIncome.subtract(monthlyExpense);
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

    private User requireAdmin(String authorization) {
        return accessControl.requireAdmin(authorization);
    }

    private TransactionRecord requireTransaction(User user, long id) {
        TransactionRecord tx = require(store.transactions.get(id), "Transaction not found");
        assertOwner(tx.userId, user.id);
        return tx;
    }

    private void validateRelationOwnership(User user, TransactionRecord tx) {
        Account account = require(store.accounts.get(tx.accountId), "Account not found");
        Category category = require(store.categories.get(tx.categoryId), "Category not found");
        assertOwner(account.userId, user.id);
        assertOwner(category.userId, user.id);
        store.attachTransactionRelations(tx);
    }

    private void applyToAccount(TransactionRecord tx, int direction) {
        Account account = store.accounts.get(tx.accountId);
        if (account == null) {
            return;
        }
        BigDecimal delta = tx.amount.multiply(BigDecimal.valueOf(direction));
        if (tx.type == 1 || tx.type == 3) {
            account.balance = account.balance.add(delta);
        } else if (tx.type == 2) {
            account.balance = account.balance.subtract(delta);
        }
        touch(account);
        store.saveAccount(account);
    }

    private Optional<Long> matchingBudgetId(TransactionRecord tx) {
        return store.budgets.values().stream()
            .filter(budget -> budget.userId == tx.userId)
            .filter(budget -> budget.status != 0)
            .filter(budget -> budget.categoryId == null || budget.categoryId.equals(tx.categoryId))
            .filter(budget -> tx.date.compareTo(budget.startDate) >= 0 && tx.date.compareTo(budget.endDate) <= 0)
            .map(budget -> budget.id)
            .findFirst();
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
        for (TransactionRecord item : store.transactions.values()) {
            if (item.userId != tx.userId) {
                continue;
            }
            store.attachTransactionRelations(item);
            boolean currentMonth = sameMonth(item.date, month);
            if (currentMonth && item.type == 1) {
                income = income.add(item.amount);
            }
            if (currentMonth && item.type == 2) {
                expense = expense.add(item.amount);
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
            } else if (item.categoryId == tx.categoryId && item.type == 2 && sameMonth(item.date, previousMonth)) {
                categoryLast = categoryLast.add(item.amount);
            }
        }
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

    private Predicate<TransactionRecord> filterTransaction(Map<String, String> params) {
        return tx -> {
            if (params.get("type") != null && tx.type != intParam(params, "type", tx.type)) {
                return false;
            }
            if (params.get("categoryId") != null && tx.categoryId != longParam(params, "categoryId", tx.categoryId)) {
                return false;
            }
            if (params.get("accountId") != null && tx.accountId != longParam(params, "accountId", tx.accountId)) {
                return false;
            }
            if (params.get("startDate") != null && tx.date.compareTo(params.get("startDate")) < 0) {
                return false;
            }
            if (params.get("endDate") != null && tx.date.compareTo(params.get("endDate")) > 0) {
                return false;
            }
            if (params.get("minAmount") != null && tx.amount.compareTo(decimalParam(params, "minAmount", tx.amount)) < 0) {
                return false;
            }
            if (params.get("maxAmount") != null && tx.amount.compareTo(decimalParam(params, "maxAmount", tx.amount)) > 0) {
                return false;
            }
            String keyword = params.getOrDefault("keyword", "").toLowerCase();
            return keyword.isBlank()
                || text(tx.note).toLowerCase().contains(keyword)
                || (tx.categoryName != null && tx.categoryName.toLowerCase().contains(keyword))
                || (tx.accountName != null && tx.accountName.toLowerCase().contains(keyword));
        };
    }

    private Predicate<Budget> filterBudget(Map<String, String> params) {
        return budget -> {
            if (params.get("status") != null && budget.status != intParam(params, "status", budget.status)) {
                return false;
            }
            if (params.get("startDate") != null && budget.endDate.compareTo(params.get("startDate")) < 0) {
                return false;
            }
            if (params.get("endDate") != null && budget.startDate.compareTo(params.get("endDate")) > 0) {
                return false;
            }
            String keyword = params.getOrDefault("keyword", "").toLowerCase();
            return keyword.isBlank() || budget.name.toLowerCase().contains(keyword);
        };
    }

    private Optional<Long> defaultLedgerId(long userId) {
        return store.ledgers.values().stream()
            .filter(ledger -> ledger.ownerId == userId && ledger.isDefault)
            .map(ledger -> ledger.id)
            .findFirst()
            .or(() -> store.ledgers.values().stream().filter(ledger -> ledger.ownerId == userId).map(ledger -> ledger.id).findFirst());
    }

    private void assertOwner(long ownerId, long currentUserId) {
        if (ownerId != currentUserId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
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

    private static int intParam(Map<String, String> params, String key, int fallback) {
        return PayloadReader.intParam(params, key, fallback);
    }

    private static long longParam(Map<String, String> params, String key, long fallback) {
        return PayloadReader.longParam(params, key, fallback);
    }

    private static BigDecimal decimalParam(Map<String, String> params, String key, BigDecimal fallback) {
        return PayloadReader.decimalParam(params, key, fallback);
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

}
