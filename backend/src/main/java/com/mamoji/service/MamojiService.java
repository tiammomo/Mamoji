package com.mamoji.service;

import com.mamoji.common.PagedResponse;
import com.mamoji.common.Permissions;
import com.mamoji.common.Roles;
import com.mamoji.domain.Models.Account;
import com.mamoji.domain.Models.Budget;
import com.mamoji.domain.Models.Category;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.Department;
import com.mamoji.domain.Models.Employee;
import com.mamoji.domain.Models.EmploymentEvent;
import com.mamoji.domain.Models.Ledger;
import com.mamoji.domain.Models.LedgerMember;
import com.mamoji.domain.Models.RecurringItem;
import com.mamoji.domain.Models.TaxItem;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MamojiService {
    private final InMemoryStore store;
    private final EnterpriseStore enterpriseStore;

    public MamojiService(InMemoryStore store, EnterpriseStore enterpriseStore) {
        this.store = store;
        this.enterpriseStore = enterpriseStore;
    }

    public Map<String, Object> login(Map<String, Object> body) {
        String email = text(body.get("email"));
        String password = text(body.get("password"));
        User user = store.findUserByEmail(email)
            .filter(candidate -> candidate.passwordHash.equals(password))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
        return authenticated(user);
    }

    public Map<String, Object> register(Map<String, Object> body) {
        String email = text(body.get("email"));
        if (email.isBlank() || text(body.get("password")).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email and password are required");
        }
        if (store.findUserByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }
        User user = store.user(
            email,
            textOr(body.get("nickname"), email.substring(0, email.indexOf("@") > 0 ? email.indexOf("@") : email.length())),
            textOr(body.get("avatar"), "😊|#3370ff"),
            text(body.get("password")),
            Roles.USER,
            Permissions.ALL
        );
        Ledger ledger = store.ledger(user.id, "公司经营账本", "初创公司经营收入、成本、税费与预算", "CNY", true);
        store.member(ledger.id, user.id, "owner");
        return authenticated(user);
    }

    public User me(String authorization) {
        return requireUser(authorization);
    }

    public User updateProfile(String authorization, Map<String, Object> body) {
        User user = requireUser(authorization);
        if (body.containsKey("nickname")) {
            user.nickname = text(body.get("nickname"));
        }
        if (body.containsKey("avatar")) {
            user.avatar = text(body.get("avatar"));
        }
        touch(user);
        store.saveUser(user);
        return user;
    }

    public Map<String, Object> changePassword(String authorization, Map<String, Object> body) {
        User user = requireUser(authorization);
        if (!user.passwordHash.equals(text(body.get("oldPassword")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Old password is incorrect");
        }
        String newPassword = text(body.get("newPassword"));
        if (newPassword.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 6 characters");
        }
        user.passwordHash = newPassword;
        touch(user);
        store.saveUser(user);
        return Map.of("success", true);
    }

    public List<Account> listAccounts(String authorization) {
        User user = requireUser(authorization);
        return store.sortedAccounts().stream().filter(account -> account.userId == user.id).toList();
    }

    public Account getAccount(String authorization, long id) {
        User user = requireUser(authorization);
        Account account = require(store.accounts.get(id), "Account not found");
        assertOwner(account.userId, user.id);
        return account;
    }

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
        store.saveAccount(account);
        return account;
    }

    public Account updateAccount(String authorization, long id, Map<String, Object> body) {
        Account account = getAccount(authorization, id);
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
        touch(account);
        store.saveAccount(account);
        return account;
    }

    public void deleteAccount(String authorization, long id) {
        Account account = getAccount(authorization, id);
        boolean used = store.transactions.values().stream().anyMatch(tx -> tx.accountId == account.id);
        if (used) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account has transactions");
        }
        store.deleteAccount(id);
    }

    public Map<String, BigDecimal> accountSummary(String authorization) {
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
        return Map.of("totalAssets", assets, "totalLiabilities", liabilities, "netWorth", assets.subtract(liabilities));
    }

    public List<Category> listCategories(String authorization, String type) {
        User user = requireUser(authorization);
        return store.sortedCategories().stream()
            .filter(category -> category.userId == user.id)
            .filter(category -> type == null || type.isBlank() || category.type.equals(type))
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
        List<Budget> budgets = store.sortedBudgets().stream()
            .filter(budget -> budget.userId == user.id)
            .filter(filterBudget(params))
            .toList();
        return PagedResponse.of(budgets, intParam(params, "page", 0), intParam(params, "size", 20));
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
        store.attachBudgetData();
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
        store.attachBudgetData();
        return budget;
    }

    public void deleteBudget(String authorization, long id) {
        Budget budget = getBudget(authorization, id);
        store.deleteBudget(budget.id);
    }

    public PagedResponse<TransactionRecord> listTransactions(String authorization, Map<String, String> params) {
        User user = requireUser(authorization);
        List<TransactionRecord> txs = store.sortedTransactions().stream()
            .filter(tx -> tx.userId == user.id)
            .filter(filterTransaction(params))
            .peek(store::attachTransactionRelations)
            .toList();
        return PagedResponse.of(txs, intParam(params, "page", 0), intParam(params, "size", 20));
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
        store.attachBudgetData();
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
        store.attachBudgetData();
        return tx;
    }

    public void deleteTransaction(String authorization, long id) {
        User user = requireUser(authorization);
        TransactionRecord tx = requireTransaction(user, id);
        applyToAccount(tx, -1);
        store.deleteTransaction(id);
        store.attachBudgetData();
    }

    public List<TransactionRecord> refundableTransactions(String authorization) {
        User user = requireUser(authorization);
        return store.sortedTransactions().stream()
            .filter(tx -> tx.userId == user.id)
            .filter(tx -> tx.type == 2 && tx.isRefundable)
            .peek(store::attachTransactionRelations)
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
        store.attachBudgetData();
        return Map.of("transaction", refund, "risk", riskFor(refund));
    }

    public Map<String, BigDecimal> overview(String authorization) {
        User user = requireUser(authorization);
        YearMonth current = YearMonth.now();
        BigDecimal income = sumTransactions(user.id, tx -> tx.type == 1 && sameMonth(tx.date, current));
        BigDecimal expense = sumTransactions(user.id, tx -> tx.type == 2 && sameMonth(tx.date, current));
        store.attachBudgetData();
        double usage = store.budgets.values().stream()
            .filter(budget -> budget.userId == user.id && budget.status != 0)
            .mapToDouble(budget -> budget.usageRate)
            .average()
            .orElse(0);
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        result.put("monthlyIncome", income);
        result.put("monthlyExpense", expense);
        result.put("monthlyBalance", income.subtract(expense));
        result.put("budgetUsageRate", BigDecimal.valueOf(usage).setScale(4, RoundingMode.HALF_UP));
        return result;
    }

    public List<Map<String, Object>> trend(String authorization, Map<String, String> params) {
        User user = requireUser(authorization);
        String period = textOr(params.get("period"), "month");
        int count = intParam(params, "limit", switch (period) {
            case "quarter" -> 4;
            case "year" -> 5;
            default -> intParam(params, "months", 6);
        });
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = Math.max(1, count) - 1; i >= 0; i--) {
            if ("quarter".equals(period)) {
                points.add(trendPoint(user.id, YearMonth.now().minusMonths(i * 3), "quarter"));
            } else if ("year".equals(period)) {
                points.add(trendPoint(user.id, YearMonth.now().minusYears(i), "year"));
            } else {
                points.add(trendPoint(user.id, YearMonth.now().minusMonths(i), "month"));
            }
        }
        return points;
    }

    public List<Map<String, Object>> categoryStats(String authorization, Map<String, String> params) {
        User user = requireUser(authorization);
        int type = transactionTypeParam(params.get("type"), 2);
        List<TransactionRecord> txs = store.transactions.values().stream()
            .filter(tx -> tx.userId == user.id && tx.type == type)
            .filter(tx -> params.get("startDate") == null || tx.date.compareTo(params.get("startDate")) >= 0)
            .filter(tx -> params.get("endDate") == null || tx.date.compareTo(params.get("endDate")) <= 0)
            .toList();
        BigDecimal total = txs.stream().map(tx -> tx.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<Long, List<TransactionRecord>> groups = new LinkedHashMap<>();
        txs.forEach(tx -> groups.computeIfAbsent(tx.categoryId, ignored -> new ArrayList<>()).add(tx));
        return groups.entrySet().stream().map(entry -> {
            Category category = store.categories.get(entry.getKey());
            BigDecimal amount = entry.getValue().stream().map(tx -> tx.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("categoryId", entry.getKey());
            row.put("categoryName", category == null ? "Unknown" : category.name);
            row.put("categoryIcon", category == null ? "💡" : category.icon);
            row.put("categoryColor", category == null ? "#6366f1" : category.color);
            row.put("amount", amount);
            row.put("percentage", total.compareTo(BigDecimal.ZERO) == 0 ? 0 : amount.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP));
            row.put("count", entry.getValue().size());
            return row;
        }).toList();
    }

    public Map<String, Object> yearly(String authorization, int year) {
        User user = requireUser(authorization);
        List<Map<String, Object>> months = new ArrayList<>();
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        for (int month = 1; month <= 12; month++) {
            YearMonth current = YearMonth.of(year, month);
            BigDecimal income = sumTransactions(user.id, tx -> tx.type == 1 && sameMonth(tx.date, current));
            BigDecimal expense = sumTransactions(user.id, tx -> tx.type == 2 && sameMonth(tx.date, current));
            totalIncome = totalIncome.add(income);
            totalExpense = totalExpense.add(expense);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", month);
            row.put("income", income);
            row.put("expense", expense);
            row.put("balance", income.subtract(expense));
            months.add(row);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("year", year);
        report.put("months", months);
        report.put("totalIncome", totalIncome);
        report.put("totalExpense", totalExpense);
        report.put("totalBalance", totalIncome.subtract(totalExpense));
        return report;
    }

    public Map<String, Object> assetLiability(String authorization) {
        Map<String, BigDecimal> summary = accountSummary(authorization);
        Map<String, Object> result = new LinkedHashMap<>(summary);
        List<Map<String, Object>> accounts = listAccounts(authorization).stream().map(account -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", account.type);
            row.put("name", account.name);
            row.put("balance", account.balance);
            return row;
        }).toList();
        result.put("accounts", accounts);
        return result;
    }

    public Map<String, Object> comparison(String authorization, Map<String, String> params) {
        User user = requireUser(authorization);
        YearMonth current = YearMonth.now();
        YearMonth previous = current.minusMonths(1);
        YearMonth previousYear = current.minusYears(1);
        BigDecimal currentExpense = sumTransactions(user.id, tx -> tx.type == 2 && sameMonth(tx.date, current));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mom", compare(currentExpense, sumTransactions(user.id, tx -> tx.type == 2 && sameMonth(tx.date, previous))));
        result.put("yoy", compare(currentExpense, sumTransactions(user.id, tx -> tx.type == 2 && sameMonth(tx.date, previousYear))));
        return result;
    }

    public Map<String, Object> insights(String authorization) {
        User user = requireUser(authorization);
        List<Map<String, Object>> large = store.sortedTransactions().stream()
            .filter(tx -> tx.userId == user.id && tx.amount.compareTo(new BigDecimal("500")) >= 0)
            .limit(5)
            .map(tx -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", tx.id);
                row.put("amount", tx.amount);
                row.put("category", tx.categoryName == null ? "Unknown" : tx.categoryName);
                row.put("date", tx.date);
                return row;
            })
            .toList();
        List<Map<String, Object>> spikes = List.of(Map.of("category", "Shopping", "current", 899, "previous", 320, "change", 579));
        store.attachBudgetData();
        List<Map<String, Object>> alerts = store.budgets.values().stream()
            .filter(budget -> budget.userId == user.id)
            .filter(budget -> budget.riskLevel.equals("high") || budget.riskLevel.equals("critical"))
            .map(budget -> Map.<String, Object>of("name", budget.name, "usageRate", budget.usageRate, "riskLevel", budget.riskLevel))
            .toList();
        return Map.of("largeTransactions", large, "categorySpikes", spikes, "budgetAlerts", alerts);
    }

    public Map<String, Integer> backupStatus(String authorization) {
        requireUser(authorization);
        return Map.of(
            "users", store.users.size(),
            "accounts", store.accounts.size(),
            "categories", store.categories.size(),
            "transactions", store.transactions.size(),
            "budgets", store.budgets.size(),
            "ledgers", store.ledgers.size()
        );
    }

    public ResponseEntity<Map<String, Object>> exportBackup(String authorization) {
        requireUser(authorization);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDisposition(ContentDisposition.attachment().filename("mamoji-backup.json").build());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", "1.0");
        payload.put("exportedAt", InMemoryStore.now());
        payload.put("data", store.snapshot());
        return ResponseEntity.ok().headers(headers).body(payload);
    }

    public Map<String, Object> validateBackup(String authorization, MultipartFile file) {
        requireUser(authorization);
        boolean valid = file != null && !file.isEmpty() && file.getOriginalFilename() != null && file.getOriginalFilename().endsWith(".json");
        return Map.of("valid", valid, "message", valid ? "Backup file looks valid" : "Please upload a non-empty .json backup");
    }

    public Map<String, Object> uploadReceipt(String authorization, MultipartFile file) {
        requireUser(authorization);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt image is required");
        }
        String filename = file.getOriginalFilename() == null ? "receipt" : file.getOriginalFilename();
        return Map.of(
            "success", true,
            "filename", filename,
            "size", file.getSize(),
            "message", "Receipt uploaded"
        );
    }

    public List<RecurringItem> listRecurring(String authorization) {
        User user = requireUser(authorization);
        return store.recurringItems.values().stream()
            .filter(item -> item.userId == user.id)
            .sorted(Comparator.comparing(item -> item.nextExecution))
            .toList();
    }

    public RecurringItem createRecurring(String authorization, Map<String, Object> body) {
        User user = requireUser(authorization);
        RecurringItem item = new RecurringItem();
        item.id = UUID.randomUUID().toString();
        item.userId = user.id;
        applyRecurring(item, body);
        item.status = 1;
        item.executionCount = 0;
        item.nextExecution = nextExecution(item);
        store.saveRecurring(item);
        return item;
    }

    public RecurringItem updateRecurring(String authorization, String id, Map<String, Object> body) {
        RecurringItem item = requireRecurring(authorization, id);
        applyRecurring(item, body);
        item.nextExecution = nextExecution(item);
        store.saveRecurring(item);
        return item;
    }

    public void deleteRecurring(String authorization, String id) {
        RecurringItem item = requireRecurring(authorization, id);
        store.deleteRecurring(item.id);
    }

    public Map<String, Object> toggleRecurring(String authorization, String id) {
        RecurringItem item = requireRecurring(authorization, id);
        item.status = item.status == 1 ? 0 : 1;
        store.saveRecurring(item);
        return Map.of("success", true, "status", item.status);
    }

    public Map<String, Object> executeRecurring(String authorization, String id) {
        RecurringItem item = requireRecurring(authorization, id);
        Map<String, Object> body = new HashMap<>();
        body.put("type", item.type);
        body.put("amount", item.amount);
        body.put("categoryId", defaultCategoryId(requireUser(authorization).id, item.type).orElse(1L));
        body.put("accountId", listAccounts(authorization).get(0).id);
        body.put("date", LocalDate.now().toString());
        body.put("note", item.note == null ? item.name : item.note);
        Map<String, Object> result = createTransaction(authorization, body);
        item.lastExecuted = LocalDate.now().toString();
        item.executionCount++;
        item.nextExecution = nextExecution(item);
        store.saveRecurring(item);
        return result;
    }

    public PagedResponse<User> listUsers(String authorization, Map<String, String> params) {
        requireAdmin(authorization);
        String keyword = params.getOrDefault("keyword", "").toLowerCase();
        List<User> users = store.users.values().stream()
            .filter(user -> keyword.isBlank() || user.email.toLowerCase().contains(keyword) || user.nickname.toLowerCase().contains(keyword))
            .sorted(Comparator.comparing(user -> user.id))
            .toList();
        return PagedResponse.of(users, intParam(params, "page", 0), intParam(params, "size", 20));
    }

    public User updateUser(String authorization, long id, Map<String, Object> body) {
        requireAdmin(authorization);
        User user = require(store.users.get(id), "User not found");
        if (body.containsKey("role")) {
            user.role = intValue(body.get("role"), user.role);
        }
        if (body.containsKey("permissions")) {
            user.permissions = intValue(body.get("permissions"), user.permissions);
        }
        touch(user);
        store.saveUser(user);
        return user;
    }

    public void deleteUser(String authorization, long id) {
        requireAdmin(authorization);
        if (store.users.size() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot delete last user");
        }
        store.deleteUser(id);
    }

    public Map<String, Object> enterpriseSummary(String authorization) {
        return enterpriseSummary(authorization, null);
    }

    public Map<String, Object> enterpriseSummary(String authorization, Long companyId) {
        User user = requireUser(authorization);
        Company company = resolveCompany(user, companyId);
        List<Employee> employees = enterpriseStore.sortedEmployees(company.id);
        List<TaxItem> taxes = enterpriseStore.sortedTaxItems(company.id);
        BigDecimal monthlyPeopleCost = employees.stream()
            .filter(employee -> !employee.status.equals("departed"))
            .map(employee -> employee.monthlyCost)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pendingTaxAmount = taxes.stream()
            .filter(item -> !item.status.equals("paid"))
            .map(item -> item.taxAmount.subtract(item.paidAmount))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        YearMonth current = YearMonth.now();
        long departuresThisMonth = employees.stream()
            .filter(employee -> employee.leaveDate != null && !employee.leaveDate.isBlank())
            .filter(employee -> sameMonth(employee.leaveDate, current))
            .count();
        long hiresThisMonth = employees.stream()
            .filter(employee -> sameMonth(employee.hireDate, current))
            .count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("company", company);
        result.put("departmentCount", enterpriseStore.sortedDepartments(company.id).size());
        result.put("employeeCount", employees.size());
        result.put("activeEmployeeCount", employees.stream().filter(employee -> employee.status.equals("active") || employee.status.equals("probation")).count());
        result.put("onboardingCount", employees.stream().filter(employee -> employee.status.equals("onboarding")).count());
        result.put("departedCount", employees.stream().filter(employee -> employee.status.equals("departed")).count());
        result.put("hiresThisMonth", hiresThisMonth);
        result.put("departuresThisMonth", departuresThisMonth);
        result.put("monthlyPeopleCost", monthlyPeopleCost);
        result.put("pendingTaxAmount", pendingTaxAmount);
        result.put("nextTaxDueDate", taxes.stream().filter(item -> !item.status.equals("paid")).map(item -> item.dueDate).min(String::compareTo).orElse(null));
        return result;
    }

    public Map<String, Object> enterprisePermissionMatrix(String authorization) {
        requireUser(authorization);
        List<Map<String, Object>> roles = List.of(
            row("key", "founder", "name", "创始人/CEO", "description", "公司所有者，负责最终经营与权限控制"),
            row("key", "finance_admin", "name", "财务管理员", "description", "管理收入、成本、账户、税费、预算和经营报表"),
            row("key", "hr_admin", "name", "人事管理员", "description", "管理员工档案、入职、离职、人力成本和人员权限"),
            row("key", "department_manager", "name", "部门负责人", "description", "管理本部门人员、预算、成本和审批"),
            row("key", "employee", "name", "普通员工", "description", "维护本人信息，提交报销和查看个人审批"),
            row("key", "viewer", "name", "只读观察者", "description", "用于投资人、顾问、审计或离职留档的只读访问")
        );
        List<Map<String, Object>> scopes = List.of(
            row("key", "group", "name", "多公司集团", "description", "可访问同一经营主体下的多家公司汇总数据"),
            row("key", "company", "name", "全公司", "description", "可访问公司级数据"),
            row("key", "company_set", "name", "指定公司集", "description", "仅访问被授权的多个公司主体"),
            row("key", "department", "name", "本部门", "description", "仅访问员工所属部门或负责部门数据"),
            row("key", "self", "name", "本人", "description", "仅访问本人档案、单据和审批"),
            row("key", "readonly", "name", "只读", "description", "可查看但不可编辑")
        );
        List<Map<String, Object>> permissions = List.of(
            row("key", "company.switch", "name", "切换公司主体"),
            row("key", "company.create", "name", "新增公司主体"),
            row("key", "company.manage", "name", "公司资料管理"),
            row("key", "policy.read", "name", "查看地区政策画像"),
            row("key", "policy.manage", "name", "维护政策配置"),
            row("key", "people.read", "name", "查看人员档案"),
            row("key", "people.write", "name", "维护人员档案"),
            row("key", "people.offboard", "name", "办理离职"),
            row("key", "finance.read", "name", "查看财务数据"),
            row("key", "finance.write", "name", "维护经营流水"),
            row("key", "budget.manage", "name", "预算管理"),
            row("key", "tax.manage", "name", "税费管理"),
            row("key", "approval.manage", "name", "审批处理"),
            row("key", "reports.read", "name", "查看经营报表"),
            row("key", "admin.permissions", "name", "权限分配")
        );
        List<Map<String, Object>> matrix = List.of(
            row("role", "founder", "defaultScope", "company", "permissions", List.of(
                "company.switch", "company.create", "company.manage", "policy.read", "policy.manage",
                "people.read", "people.write", "people.offboard", "finance.read", "finance.write",
                "budget.manage", "tax.manage", "approval.manage", "reports.read", "admin.permissions"
            )),
            row("role", "finance_admin", "defaultScope", "company", "permissions", List.of(
                "company.switch", "policy.read", "finance.read", "finance.write", "budget.manage", "tax.manage", "approval.manage", "reports.read"
            )),
            row("role", "hr_admin", "defaultScope", "company", "permissions", List.of(
                "company.switch", "policy.read", "people.read", "people.write", "people.offboard", "approval.manage", "reports.read"
            )),
            row("role", "department_manager", "defaultScope", "department", "permissions", List.of(
                "people.read", "finance.read", "budget.manage", "approval.manage", "reports.read"
            )),
            row("role", "employee", "defaultScope", "self", "permissions", List.of(
                "people.read", "approval.manage"
            )),
            row("role", "viewer", "defaultScope", "readonly", "permissions", List.of(
                "people.read", "finance.read", "reports.read"
            ))
        );
        return Map.of("roles", roles, "scopes", scopes, "permissions", permissions, "matrix", matrix);
    }

    public List<Company> listCompanies(String authorization) {
        return accessibleCompanies(requireUser(authorization));
    }

    public Company createCompany(String authorization, Map<String, Object> body) {
        User user = requireUser(authorization);
        Company company = enterpriseStore.company(
            user.id,
            textOr(body.get("name"), "新公司主体"),
            nullableText(body.get("creditCode")),
            textOr(body.get("industry"), "未设置"),
            textOr(body.get("taxpayerType"), "未设置"),
            textOr(body.get("currency"), "CNY")
        );
        applyCompanyFields(company, body);
        touch(company);
        enterpriseStore.saveCompany(company);
        return company;
    }

    public Company companyProfile(String authorization) {
        return companyProfile(authorization, null);
    }

    public Company companyProfile(String authorization, Long companyId) {
        return resolveCompany(requireUser(authorization), companyId);
    }

    public Company updateCompanyProfile(String authorization, Map<String, Object> body) {
        return updateCompanyProfile(authorization, null, body);
    }

    public Company updateCompanyProfile(String authorization, Long companyId, Map<String, Object> body) {
        requirePeopleManager(authorization);
        Company company = resolveCompany(requireUser(authorization), companyId);
        applyCompanyFields(company, body);
        touch(company);
        enterpriseStore.saveCompany(company);
        return company;
    }

    private void applyCompanyFields(Company company, Map<String, Object> body) {
        if (body.containsKey("name")) {
            company.name = text(body.get("name"));
        }
        if (body.containsKey("creditCode")) {
            company.creditCode = nullableText(body.get("creditCode"));
        }
        if (body.containsKey("industry")) {
            company.industry = text(body.get("industry"));
        }
        if (body.containsKey("taxpayerType")) {
            company.taxpayerType = text(body.get("taxpayerType"));
        }
        if (body.containsKey("currency")) {
            company.currency = text(body.get("currency"));
        }
        if (body.containsKey("country")) {
            company.country = text(body.get("country"));
        }
        if (body.containsKey("province")) {
            company.province = text(body.get("province"));
        }
        if (body.containsKey("city")) {
            company.city = text(body.get("city"));
        }
        if (body.containsKey("district")) {
            company.district = text(body.get("district"));
        }
        if (body.containsKey("registeredAddress")) {
            company.registeredAddress = nullableText(body.get("registeredAddress"));
        }
        if (body.containsKey("operatingRegion")) {
            company.operatingRegion = text(body.get("operatingRegion"));
        } else if (body.keySet().stream().anyMatch(key -> List.of("country", "province", "city", "district").contains(key))) {
            company.operatingRegion = List.of(company.country, company.province, company.city, company.district).stream()
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "/" + right)
                .orElse("中国");
        }
        if (body.containsKey("taxAuthority")) {
            company.taxAuthority = nullableText(body.get("taxAuthority"));
        }
        if (body.containsKey("policyProfileKey")) {
            company.policyProfileKey = text(body.get("policyProfileKey"));
        }
        if (body.containsKey("fiscalYearStartMonth")) {
            company.fiscalYearStartMonth = intValue(body.get("fiscalYearStartMonth"), company.fiscalYearStartMonth);
        }
    }

    public List<Department> listDepartments(String authorization) {
        return listDepartments(authorization, null);
    }

    public List<Department> listDepartments(String authorization, Long companyId) {
        Company company = resolveCompany(requireUser(authorization), companyId);
        return enterpriseStore.sortedDepartments(company.id);
    }

    public Department createDepartment(String authorization, Map<String, Object> body) {
        requirePeopleManager(authorization);
        Company company = resolveCompany(requireUser(authorization), optionalLong(body.get("companyId")).orElse(null));
        return enterpriseStore.department(
            company.id,
            textOr(body.get("name"), "新部门"),
            textOr(body.get("costCenter"), "GENERAL"),
            String.valueOf(number(body.get("budget"), BigDecimal.ZERO))
        );
    }

    public List<Employee> listEmployees(String authorization, Map<String, String> params) {
        Company company = resolveCompany(requireUser(authorization), optionalLong(params.get("companyId")).orElse(null));
        String keyword = params.getOrDefault("keyword", "").toLowerCase();
        String status = params.getOrDefault("status", "");
        long departmentId = longParam(params, "departmentId", 0);
        return enterpriseStore.sortedEmployees(company.id).stream()
            .filter(employee -> keyword.isBlank()
                || employee.name.toLowerCase().contains(keyword)
                || employee.email.toLowerCase().contains(keyword)
                || employee.position.toLowerCase().contains(keyword)
                || (employee.departmentName != null && employee.departmentName.toLowerCase().contains(keyword)))
            .filter(employee -> status.isBlank() || employee.status.equals(status))
            .filter(employee -> departmentId == 0 || (employee.departmentId != null && employee.departmentId == departmentId))
            .toList();
    }

    public Employee createEmployee(String authorization, Map<String, Object> body) {
        User operator = requirePeopleManager(authorization);
        Company company = resolveCompany(operator, optionalLong(body.get("companyId")).orElse(null));
        Employee employee = enterpriseStore.employee(
            company.id,
            optionalLong(body.get("userId")).orElse(null),
            optionalLong(body.get("departmentId")).orElse(null),
            textOr(body.get("name"), "新员工"),
            textOr(body.get("email"), "employee-" + System.currentTimeMillis() + "@mamoji.local"),
            nullableText(body.get("phone")),
            textOr(body.get("position"), "团队成员"),
            textOr(body.get("employmentType"), "full_time"),
            textOr(body.get("status"), "onboarding"),
            textOr(body.get("accessRole"), "employee"),
            textOr(body.get("accessScope"), "self"),
            textOr(body.get("hireDate"), LocalDate.now().toString()),
            nullableText(body.get("leaveDate")),
            String.valueOf(number(body.get("salary"), BigDecimal.ZERO)),
            String.valueOf(number(body.get("socialInsurance"), BigDecimal.ZERO)),
            String.valueOf(number(body.get("housingFund"), BigDecimal.ZERO)),
            String.valueOf(number(body.get("taxEstimate"), BigDecimal.ZERO)),
            null,
            nullableText(body.get("emergencyContact"))
        );
        enterpriseStore.event(company.id, employee.id, "onboard", employee.hireDate, "新增员工档案", operator.id);
        return employee;
    }

    public Employee updateEmployee(String authorization, long id, Map<String, Object> body) {
        User operator = requirePeopleManager(authorization);
        Employee employee = require(enterpriseStore.employees.get(id), "Employee not found");
        if (!canAccessCompany(operator, employee.companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        String oldStatus = employee.status;
        if (body.containsKey("userId")) {
            employee.userId = optionalLong(body.get("userId")).orElse(null);
        }
        if (body.containsKey("departmentId")) {
            employee.departmentId = optionalLong(body.get("departmentId")).orElse(null);
        }
        if (body.containsKey("name")) {
            employee.name = text(body.get("name"));
        }
        if (body.containsKey("email")) {
            employee.email = text(body.get("email"));
        }
        if (body.containsKey("phone")) {
            employee.phone = nullableText(body.get("phone"));
        }
        if (body.containsKey("position")) {
            employee.position = text(body.get("position"));
        }
        if (body.containsKey("employmentType")) {
            employee.employmentType = text(body.get("employmentType"));
        }
        if (body.containsKey("status")) {
            employee.status = text(body.get("status"));
        }
        if (body.containsKey("accessRole")) {
            employee.accessRole = text(body.get("accessRole"));
        }
        if (body.containsKey("accessScope")) {
            employee.accessScope = text(body.get("accessScope"));
        }
        if (body.containsKey("hireDate")) {
            employee.hireDate = text(body.get("hireDate"));
        }
        if (body.containsKey("leaveDate")) {
            employee.leaveDate = nullableText(body.get("leaveDate"));
        }
        if (body.containsKey("salary")) {
            employee.salary = number(body.get("salary"), employee.salary);
        }
        if (body.containsKey("socialInsurance")) {
            employee.socialInsurance = number(body.get("socialInsurance"), employee.socialInsurance);
        }
        if (body.containsKey("housingFund")) {
            employee.housingFund = number(body.get("housingFund"), employee.housingFund);
        }
        if (body.containsKey("taxEstimate")) {
            employee.taxEstimate = number(body.get("taxEstimate"), employee.taxEstimate);
        }
        if (body.containsKey("emergencyContact")) {
            employee.emergencyContact = nullableText(body.get("emergencyContact"));
        }
        touch(employee);
        enterpriseStore.saveEmployee(employee);
        if (!oldStatus.equals(employee.status)) {
            String eventType = employee.status.equals("departed") ? "offboard" : "status_change";
            String effectiveDate = employee.status.equals("departed") && employee.leaveDate != null ? employee.leaveDate : LocalDate.now().toString();
            enterpriseStore.event(employee.companyId, employee.id, eventType, effectiveDate, "员工状态从 " + oldStatus + " 更新为 " + employee.status, operator.id);
        }
        return employee;
    }

    public List<EmploymentEvent> listEmploymentEvents(String authorization) {
        return listEmploymentEvents(authorization, null);
    }

    public List<EmploymentEvent> listEmploymentEvents(String authorization, Long companyId) {
        Company company = resolveCompany(requireUser(authorization), companyId);
        return enterpriseStore.sortedEmploymentEvents(company.id);
    }

    public List<TaxItem> listTaxItems(String authorization) {
        return listTaxItems(authorization, null);
    }

    public List<TaxItem> listTaxItems(String authorization, Long companyId) {
        Company company = resolveCompany(requireUser(authorization), companyId);
        return enterpriseStore.sortedTaxItems(company.id);
    }

    public TaxItem updateTaxItem(String authorization, long id, Map<String, Object> body) {
        requireAdmin(authorization);
        User user = requireUser(authorization);
        TaxItem item = require(enterpriseStore.taxItems.get(id), "Tax item not found");
        if (!canAccessCompany(user, item.companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if (body.containsKey("status")) {
            item.status = text(body.get("status"));
        }
        if (body.containsKey("paidAmount")) {
            item.paidAmount = number(body.get("paidAmount"), item.paidAmount);
        }
        if (body.containsKey("note")) {
            item.note = nullableText(body.get("note"));
        }
        touch(item);
        enterpriseStore.saveTaxItem(item);
        return item;
    }

    public List<Ledger> listLedgers(String authorization) {
        User user = requireUser(authorization);
        return store.ledgers.values().stream()
            .filter(ledger -> ledger.ownerId == user.id || isLedgerMember(ledger.id, user.id))
            .sorted(Comparator.comparing(ledger -> ledger.id))
            .toList();
    }

    public Ledger defaultLedger(String authorization) {
        User user = requireUser(authorization);
        return defaultLedgerId(user.id)
            .map(id -> store.ledgers.get(id))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Default ledger not found"));
    }

    public Ledger getLedger(String authorization, long id) {
        User user = requireUser(authorization);
        Ledger ledger = require(store.ledgers.get(id), "Ledger not found");
        if (ledger.ownerId != user.id && !isLedgerMember(ledger.id, user.id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to ledger");
        }
        return ledger;
    }

    public Ledger createLedger(String authorization, Map<String, Object> body) {
        User user = requireUser(authorization);
        Ledger ledger = store.ledger(
            user.id,
            textOr(body.get("name"), "新账本"),
            textOr(body.get("description"), ""),
            textOr(body.get("currency"), "CNY"),
            false
        );
        store.member(ledger.id, user.id, "owner");
        return ledger;
    }

    public List<LedgerMember> ledgerMembers(String authorization, long ledgerId) {
        getLedger(authorization, ledgerId);
        return store.ledgerMembers.values().stream()
            .filter(member -> member.ledgerId == ledgerId)
            .sorted(Comparator.comparing(member -> member.id))
            .toList();
    }

    public Map<String, Object> addLedgerMember(String authorization, long ledgerId, Map<String, Object> body) {
        Ledger ledger = getLedger(authorization, ledgerId);
        User current = requireUser(authorization);
        if (ledger.ownerId != current.id) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner can add members");
        }
        long userId = longValue(body.get("userId"), 0);
        require(store.users.get(userId), "User not found");
        store.member(ledgerId, userId, textOr(body.get("role"), "viewer"));
        return Map.of("success", true);
    }

    public void removeLedgerMember(String authorization, long ledgerId, long userId) {
        Ledger ledger = getLedger(authorization, ledgerId);
        User current = requireUser(authorization);
        if (ledger.ownerId != current.id) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner can remove members");
        }
        store.deleteLedgerMember(ledgerId, userId);
    }

    private Company defaultCompany(User user) {
        List<Company> companies = accessibleCompanies(user);
        if (!companies.isEmpty()) {
            return companies.get(0);
        }
        return enterpriseStore.sortedCompanies().stream()
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));
    }

    private Company resolveCompany(User user, Long companyId) {
        if (companyId == null || companyId == 0) {
            return defaultCompany(user);
        }
        Company company = require(enterpriseStore.companies.get(companyId), "Company not found");
        if (!canAccessCompany(user, company.id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to company");
        }
        return company;
    }

    private List<Company> accessibleCompanies(User user) {
        if (user.role == Roles.ADMIN) {
            return enterpriseStore.sortedCompanies();
        }
        return enterpriseStore.sortedCompanies().stream()
            .filter(company -> company.ownerId == user.id || enterpriseStore.employees.values().stream()
                .anyMatch(employee -> employee.companyId == company.id && employee.userId != null && employee.userId == user.id))
            .toList();
    }

    private boolean canAccessCompany(User user, long companyId) {
        return user.role == Roles.ADMIN || accessibleCompanies(user).stream().anyMatch(company -> company.id == companyId);
    }

    private User requirePeopleManager(String authorization) {
        User user = requireUser(authorization);
        Optional<Employee> employee = enterpriseStore.employees.values().stream()
            .filter(candidate -> candidate.userId != null && candidate.userId == user.id)
            .findFirst();
        boolean peopleRole = employee
            .map(candidate -> candidate.accessRole.equals("founder") || candidate.accessRole.equals("hr_admin"))
            .orElse(false);
        if (user.role == Roles.ADMIN || peopleRole || (user.permissions & Permissions.USER) != 0) {
            return user;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "People management permission required");
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            row.put(String.valueOf(values[index]), values[index + 1]);
        }
        return row;
    }

    private Map<String, Object> authenticated(User user) {
        String token = UUID.randomUUID().toString();
        store.rememberToken(token, user.id);
        return Map.of("token", token, "user", user);
    }

    private User requireUser(String authorization) {
        return store.currentUser(authorization)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized"));
    }

    private User requireAdmin(String authorization) {
        User user = requireUser(authorization);
        if (user.role != Roles.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
        return user;
    }

    private TransactionRecord requireTransaction(User user, long id) {
        TransactionRecord tx = require(store.transactions.get(id), "Transaction not found");
        assertOwner(tx.userId, user.id);
        return tx;
    }

    private RecurringItem requireRecurring(String authorization, String id) {
        User user = requireUser(authorization);
        RecurringItem item = require(store.recurringItems.get(id), "Recurring item not found");
        assertOwner(item.userId, user.id);
        return item;
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
        BigDecimal income = sumTransactions(tx.userId, item -> item.type == 1 && sameMonth(item.date, month));
        BigDecimal expense = sumTransactions(tx.userId, item -> item.type == 2 && sameMonth(item.date, month));
        long dailyExpenseCount = store.transactions.values().stream()
            .filter(item -> item.userId == tx.userId && item.type == 2 && item.date.equals(tx.date))
            .count();
        long duplicateCount = store.transactions.values().stream()
            .filter(item -> item.userId == tx.userId && item.id != tx.id)
            .filter(item -> item.type == tx.type && item.categoryId == tx.categoryId && item.accountId == tx.accountId)
            .filter(item -> item.amount.compareTo(tx.amount) == 0 && item.date.equals(tx.date))
            .count();
        BigDecimal categoryCurrent = sumTransactions(tx.userId, item -> item.categoryId == tx.categoryId && item.type == 2 && sameMonth(item.date, month));
        BigDecimal categoryLast = sumTransactions(tx.userId, item -> item.categoryId == tx.categoryId && item.type == 2 && sameMonth(item.date, month.minusMonths(1)));
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

    private BigDecimal sumTransactions(long userId, Predicate<TransactionRecord> predicate) {
        return store.transactions.values().stream()
            .filter(tx -> tx.userId == userId)
            .peek(store::attachTransactionRelations)
            .filter(predicate)
            .map(tx -> tx.amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<String, Object> trendPoint(long userId, YearMonth anchor, String period) {
        Predicate<TransactionRecord> range = switch (period) {
            case "quarter" -> tx -> sameQuarter(tx.date, anchor);
            case "year" -> tx -> sameYear(tx.date, anchor.getYear());
            default -> tx -> sameMonth(tx.date, anchor);
        };
        BigDecimal income = sumTransactions(userId, tx -> tx.type == 1 && range.test(tx));
        BigDecimal expense = sumTransactions(userId, tx -> tx.type == 2 && range.test(tx));
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("month", trendLabel(anchor, period));
        point.put("period", period);
        point.put("income", income);
        point.put("expense", expense);
        point.put("balance", income.subtract(expense));
        point.put("hasData", income.compareTo(BigDecimal.ZERO) != 0 || expense.compareTo(BigDecimal.ZERO) != 0);
        return point;
    }

    private Predicate<TransactionRecord> filterTransaction(Map<String, String> params) {
        return tx -> {
            if (params.get("type") != null && tx.type != intParam(params, "type", tx.type)) {
                return false;
            }
            if (params.get("categoryId") != null && tx.categoryId != longParam(params, "categoryId", tx.categoryId)) {
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
                || tx.note.toLowerCase().contains(keyword)
                || (tx.categoryName != null && tx.categoryName.toLowerCase().contains(keyword));
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

    private Map<String, Object> compare(BigDecimal current, BigDecimal previous) {
        BigDecimal change = current.subtract(previous);
        BigDecimal changePercent = previous.compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.ZERO
            : change.multiply(BigDecimal.valueOf(100)).divide(previous, 2, RoundingMode.HALF_UP);
        return Map.of("current", current, "previous", previous, "change", change, "changePercent", changePercent);
    }

    private void applyRecurring(RecurringItem item, Map<String, Object> body) {
        if (body.containsKey("name")) {
            item.name = text(body.get("name"));
        }
        if (body.containsKey("type")) {
            item.type = intValue(body.get("type"), item.type == 0 ? 2 : item.type);
        }
        if (body.containsKey("amount")) {
            item.amount = number(body.get("amount"), item.amount == null ? BigDecimal.ZERO : item.amount);
        }
        if (body.containsKey("frequency")) {
            item.frequency = text(body.get("frequency"));
        }
        if (body.containsKey("interval")) {
            item.interval = intValue(body.get("interval"), item.interval == 0 ? 1 : item.interval);
        }
        if (body.containsKey("dayOfWeek")) {
            item.dayOfWeek = optionalInt(body.get("dayOfWeek")).orElse(null);
        }
        if (body.containsKey("dayOfMonth")) {
            item.dayOfMonth = optionalInt(body.get("dayOfMonth")).orElse(null);
        }
        if (body.containsKey("monthOfYear")) {
            item.monthOfYear = optionalInt(body.get("monthOfYear")).orElse(null);
        }
        if (body.containsKey("startDate")) {
            item.startDate = text(body.get("startDate"));
        }
        if (body.containsKey("endDate")) {
            item.endDate = nullableText(body.get("endDate"));
        }
        if (body.containsKey("note")) {
            item.note = nullableText(body.get("note"));
        }
        if (item.name == null) {
            item.name = "周期项目";
        }
        if (item.frequency == null) {
            item.frequency = "monthly";
        }
        if (item.interval == 0) {
            item.interval = 1;
        }
        if (item.amount == null) {
            item.amount = BigDecimal.ZERO;
        }
        if (item.startDate == null) {
            item.startDate = LocalDate.now().toString();
        }
    }

    private String nextExecution(RecurringItem item) {
        LocalDate base = item.lastExecuted == null ? LocalDate.parse(item.startDate) : LocalDate.parse(item.lastExecuted);
        return switch (item.frequency) {
            case "daily" -> base.plusDays(item.interval).toString();
            case "weekly" -> base.plusWeeks(item.interval).toString();
            case "yearly" -> base.plusYears(item.interval).toString();
            default -> base.plusMonths(item.interval).toString();
        };
    }

    private Optional<Long> defaultLedgerId(long userId) {
        return store.ledgers.values().stream()
            .filter(ledger -> ledger.ownerId == userId && ledger.isDefault)
            .map(ledger -> ledger.id)
            .findFirst()
            .or(() -> store.ledgers.values().stream().filter(ledger -> ledger.ownerId == userId).map(ledger -> ledger.id).findFirst());
    }

    private Optional<Long> defaultCategoryId(long userId, int type) {
        String typeName = type == 1 ? "income" : "expense";
        return store.categories.values().stream()
            .filter(category -> category.userId == userId && category.type.equals(typeName))
            .map(category -> category.id)
            .findFirst();
    }

    private boolean isLedgerMember(long ledgerId, long userId) {
        return store.ledgerMembers.values().stream().anyMatch(member -> member.ledgerId == ledgerId && member.userId == userId);
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

    private static boolean sameMonth(String date, YearMonth month) {
        return YearMonth.from(LocalDate.parse(date)).equals(month);
    }

    private static boolean sameQuarter(String date, YearMonth anchor) {
        YearMonth value = YearMonth.from(LocalDate.parse(date));
        return value.getYear() == anchor.getYear() && quarterOf(value) == quarterOf(anchor);
    }

    private static boolean sameYear(String date, int year) {
        return LocalDate.parse(date).getYear() == year;
    }

    private static String trendLabel(YearMonth anchor, String period) {
        return switch (period) {
            case "quarter" -> anchor.getYear() + " Q" + quarterOf(anchor);
            case "year" -> String.valueOf(anchor.getYear());
            default -> anchor.toString();
        };
    }

    private static int quarterOf(YearMonth month) {
        return ((month.getMonthValue() - 1) / 3) + 1;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String textOr(Object value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : text;
    }

    private static String nullableText(Object value) {
        if (value == null) {
            return null;
        }
        String text = text(value);
        return text.isBlank() ? null : text;
    }

    private static BigDecimal number(Object value, BigDecimal fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static Optional<Long> optionalLong(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(String.valueOf(value)));
    }

    private static Optional<Integer> optionalInt(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(String.valueOf(value)));
    }

    private static long longValue(Object value, long fallback) {
        return optionalLong(value).orElse(fallback);
    }

    private static int intValue(Object value, int fallback) {
        return optionalInt(value).orElse(fallback);
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intParam(Map<String, String> params, String key, int fallback) {
        try {
            return params.get(key) == null ? fallback : Integer.parseInt(params.get(key));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longParam(Map<String, String> params, String key, long fallback) {
        try {
            return params.get(key) == null ? fallback : Long.parseLong(params.get(key));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static BigDecimal decimalParam(Map<String, String> params, String key, BigDecimal fallback) {
        try {
            return params.get(key) == null ? fallback : new BigDecimal(params.get(key));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int transactionTypeParam(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.toLowerCase()) {
            case "income" -> 1;
            case "expense" -> 2;
            case "refund" -> 3;
            default -> {
                try {
                    yield Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                    yield fallback;
                }
            }
        };
    }
}
