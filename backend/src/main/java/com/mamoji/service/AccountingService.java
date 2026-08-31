package com.mamoji.service;

import com.mamoji.common.PayloadReader;
import com.mamoji.domain.Models.Account;
import com.mamoji.domain.Models.Category;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountingService {
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

    private long defaultLedgerId(User user, Company company) {
        return store.queryLedgers(user.id, company.id).stream()
            .map(ledger -> ledger.id)
            .findFirst()
            .orElseGet(() -> store.ensureCompanyAccountingWorkspace(user.id, company.id, company.currency, company.name).id);
    }

    private Company resolveCompany(User user, Map<String, ?> values) {
        return accessControl.resolveCompany(user, optionalLong(values.get("companyId")).orElse(null));
    }

    private void assertScopedOwner(long ownerId, Long recordCompanyId, long currentUserId, long companyId) {
        if (ownerId != currentUserId || !Objects.equals(recordCompanyId, companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private Account copyAccount(Account source) {
        return copyModel(source, new Account());
    }

    private Category copyCategory(Category source) {
        return copyModel(source, new Category());
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

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

}
