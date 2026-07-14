package com.mamoji.service;

import com.mamoji.domain.Models.Account;
import com.mamoji.domain.Models.Category;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.domain.Models.User;
import com.mamoji.domain.Models.Company;
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
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.intParam;
import static com.mamoji.common.PayloadReader.optionalLong;

@Service
public class ReportingService {
    private static final Comparator<TransactionRecord> TRANSACTION_ORDER =
        Comparator.comparing((TransactionRecord tx) -> tx.date).reversed().thenComparing(tx -> tx.id);

    private final InMemoryStore store;
    private final AccessControlService accessControl;

    public ReportingService(InMemoryStore store, AccessControlService accessControl) {
        this.store = store;
        this.accessControl = accessControl;
    }

    public Map<String, BigDecimal> overview(String authorization) {
        return overview(authorization, Map.of());
    }

    public Map<String, BigDecimal> overview(String authorization, Map<String, String> params) {
        Scope scope = scope(authorization, params);
        long userId = scope.userId();
        YearMonth current = parseMonth(params.get("month"));
        List<TransactionRecord> txs = userTransactions(userId, scope.companyId());
        BigDecimal income = sumTransactions(txs, tx -> tx.type == 1 && sameMonth(tx.date, current));
        BigDecimal expense = netExpense(txs, tx -> sameMonth(tx.date, current));
        store.attachBudgetData();
        List<com.mamoji.domain.Models.Budget> matchingBudgets = store.budgets.values().stream()
            .filter(budget -> budget.userId == userId && budget.status != 0)
            .filter(budget -> Objects.equals(budget.companyId, scope.companyId()))
            .filter(budget -> budget.startDate.compareTo(current.atEndOfMonth().toString()) <= 0)
            .filter(budget -> budget.endDate.compareTo(current.atDay(1).toString()) >= 0)
            .toList();
        BigDecimal budgetAmount = matchingBudgets.stream().map(budget -> budget.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal budgetSpent = matchingBudgets.stream().map(budget -> budget.spent).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal usage = budgetAmount.compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.ZERO
            : budgetSpent.divide(budgetAmount, 4, RoundingMode.HALF_UP);
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        result.put("monthlyIncome", income);
        result.put("monthlyExpense", expense);
        result.put("monthlyBalance", income.subtract(expense));
        result.put("budgetUsageRate", usage);
        return result;
    }

    public List<Map<String, Object>> trend(String authorization, Map<String, String> params) {
        Scope scope = scope(authorization, params);
        List<TransactionRecord> txs = userTransactions(scope.userId(), scope.companyId());
        String period = params.getOrDefault("period", "month");
        int count = Math.min(60, Math.max(1, intParam(params, "limit", switch (period) {
            case "quarter" -> 4;
            case "year" -> 5;
            default -> intParam(params, "months", 6);
        })));
        YearMonth anchor = parseAnchorDate(params.get("endDate"));
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            if ("quarter".equals(period)) {
                points.add(trendPoint(txs, anchor.minusMonths(i * 3L), "quarter"));
            } else if ("year".equals(period)) {
                points.add(trendPoint(txs, anchor.minusYears(i), "year"));
            } else {
                points.add(trendPoint(txs, anchor.minusMonths(i), "month"));
            }
        }
        return points;
    }

    public List<Map<String, Object>> categoryStats(String authorization, Map<String, String> params) {
        Scope scope = scope(authorization, params);
        long userId = scope.userId();
        int type = transactionTypeParam(params.get("type"), 2);
        List<TransactionRecord> txs = store.transactions.values().stream()
            .filter(tx -> tx.userId == userId && Objects.equals(tx.companyId, scope.companyId()))
            .filter(tx -> type == 2 ? tx.type == 2 || tx.type == 3 : tx.type == type)
            .filter(tx -> params.get("startDate") == null || tx.date.compareTo(params.get("startDate")) >= 0)
            .filter(tx -> params.get("endDate") == null || tx.date.compareTo(params.get("endDate")) <= 0)
            .toList();
        BigDecimal total = txs.stream().map(tx -> signedAmount(tx, type)).reduce(BigDecimal.ZERO, BigDecimal::add).max(BigDecimal.ZERO);
        Map<Long, List<TransactionRecord>> groups = new LinkedHashMap<>();
        txs.forEach(tx -> groups.computeIfAbsent(tx.categoryId, ignored -> new ArrayList<>()).add(tx));
        return groups.entrySet().stream().map(entry -> {
            Category category = store.categories.get(entry.getKey());
            BigDecimal amount = entry.getValue().stream().map(tx -> signedAmount(tx, type)).reduce(BigDecimal.ZERO, BigDecimal::add).max(BigDecimal.ZERO);
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
        return yearly(authorization, year, null);
    }

    public Map<String, Object> yearly(String authorization, int year, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        List<TransactionRecord> txs = userTransactions(user.id, company.id);
        List<Map<String, Object>> months = new ArrayList<>();
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        for (int month = 1; month <= 12; month++) {
            YearMonth current = YearMonth.of(year, month);
            BigDecimal income = sumTransactions(txs, tx -> tx.type == 1 && sameMonth(tx.date, current));
            BigDecimal expense = netExpense(txs, tx -> sameMonth(tx.date, current));
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
        return assetLiability(authorization, null);
    }

    public Map<String, Object> assetLiability(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        long userId = user.id;
        Map<String, BigDecimal> summary = accountSummary(userId, company.id);
        Map<String, Object> result = new LinkedHashMap<>(summary);
        List<Map<String, Object>> accounts = store.accounts.values().stream()
            .filter(account -> account.userId == userId)
            .filter(account -> Objects.equals(account.companyId, company.id))
            .sorted(Comparator.comparing(account -> account.id))
            .map(account -> {
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
        Scope scope = scope(authorization, params);
        List<TransactionRecord> txs = userTransactions(scope.userId(), scope.companyId());
        YearMonth current = parseMonth(params.get("month"));
        YearMonth previous = current.minusMonths(1);
        YearMonth previousYear = current.minusYears(1);
        BigDecimal currentExpense = netExpense(txs, tx -> sameMonth(tx.date, current));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mom", compare(currentExpense, netExpense(txs, tx -> sameMonth(tx.date, previous))));
        result.put("yoy", compare(currentExpense, netExpense(txs, tx -> sameMonth(tx.date, previousYear))));
        return result;
    }

    public Map<String, Object> insights(String authorization) {
        return insights(authorization, null);
    }

    public Map<String, Object> insights(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        long userId = user.id;
        List<TransactionRecord> txs = userTransactions(userId, company.id);
        List<Map<String, Object>> large = txs.stream()
            .filter(tx -> tx.amount.compareTo(new BigDecimal("500")) >= 0)
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
        List<Map<String, Object>> spikes = categorySpikes(txs);
        store.attachBudgetData();
        List<Map<String, Object>> alerts = store.budgets.values().stream()
            .filter(budget -> budget.userId == userId)
            .filter(budget -> Objects.equals(budget.companyId, company.id))
            .filter(budget -> budget.riskLevel.equals("high") || budget.riskLevel.equals("critical"))
            .map(budget -> Map.<String, Object>of("name", budget.name, "usageRate", budget.usageRate, "riskLevel", budget.riskLevel))
            .toList();
        return Map.of("largeTransactions", large, "categorySpikes", spikes, "budgetAlerts", alerts);
    }

    private Map<String, BigDecimal> accountSummary(long userId, long companyId) {
        List<Account> accounts = store.accounts.values().stream()
            .filter(account -> account.userId == userId)
            .filter(account -> Objects.equals(account.companyId, companyId))
            .toList();
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

    private List<TransactionRecord> userTransactions(long userId, long companyId) {
        return store.transactions.values().stream()
            .filter(tx -> tx.userId == userId)
            .filter(tx -> Objects.equals(tx.companyId, companyId))
            .peek(store::attachTransactionRelations)
            .sorted(TRANSACTION_ORDER)
            .toList();
    }

    private BigDecimal sumTransactions(List<TransactionRecord> transactions, Predicate<TransactionRecord> predicate) {
        return transactions.stream()
            .filter(predicate)
            .map(tx -> tx.amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal netExpense(List<TransactionRecord> transactions, Predicate<TransactionRecord> range) {
        BigDecimal expenses = sumTransactions(transactions, tx -> tx.type == 2 && range.test(tx));
        BigDecimal refunds = sumTransactions(transactions, tx -> tx.type == 3 && range.test(tx));
        return expenses.subtract(refunds).max(BigDecimal.ZERO);
    }

    private BigDecimal signedAmount(TransactionRecord tx, int requestedType) {
        return requestedType == 2 && tx.type == 3 ? tx.amount.negate() : tx.amount;
    }

    private Map<String, Object> trendPoint(List<TransactionRecord> transactions, YearMonth anchor, String period) {
        Predicate<TransactionRecord> range = switch (period) {
            case "quarter" -> tx -> sameQuarter(tx.date, anchor);
            case "year" -> tx -> sameYear(tx.date, anchor.getYear());
            default -> tx -> sameMonth(tx.date, anchor);
        };
        BigDecimal income = sumTransactions(transactions, tx -> tx.type == 1 && range.test(tx));
        BigDecimal expense = netExpense(transactions, range);
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("month", trendLabel(anchor, period));
        point.put("period", period);
        point.put("income", income);
        point.put("expense", expense);
        point.put("balance", income.subtract(expense));
        point.put("hasData", income.compareTo(BigDecimal.ZERO) != 0 || expense.compareTo(BigDecimal.ZERO) != 0);
        return point;
    }

    private List<Map<String, Object>> categorySpikes(List<TransactionRecord> transactions) {
        YearMonth current = YearMonth.now();
        YearMonth previous = current.minusMonths(1);
        Map<Long, BigDecimal> currentByCategory = new HashMap<>();
        Map<Long, BigDecimal> previousByCategory = new HashMap<>();
        transactions.stream()
            .filter(tx -> tx.type == 2 || tx.type == 3)
            .forEach(tx -> {
                BigDecimal signed = tx.type == 3 ? tx.amount.negate() : tx.amount;
                if (sameMonth(tx.date, current)) {
                    currentByCategory.merge(tx.categoryId, signed, BigDecimal::add);
                } else if (sameMonth(tx.date, previous)) {
                    previousByCategory.merge(tx.categoryId, signed, BigDecimal::add);
                }
            });
        List<Map<String, Object>> rows = new ArrayList<>();
        currentByCategory.forEach((categoryId, currentAmount) -> {
            BigDecimal previousAmount = previousByCategory.getOrDefault(categoryId, BigDecimal.ZERO);
            BigDecimal change = currentAmount.subtract(previousAmount);
            if (change.compareTo(BigDecimal.ZERO) <= 0) {
                return;
            }
            Category category = store.categories.get(categoryId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("categoryId", categoryId);
            row.put("category", category == null ? "Unknown" : category.name);
            row.put("current", currentAmount);
            row.put("previous", previousAmount);
            row.put("change", change);
            row.put("changePercent", previousAmount.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : change.multiply(BigDecimal.valueOf(100)).divide(previousAmount, 2, RoundingMode.HALF_UP));
            rows.add(row);
        });
        return rows.stream()
            .sorted(Comparator.comparing(row -> (BigDecimal) row.get("change"), Comparator.reverseOrder()))
            .limit(5)
            .toList();
    }

    private Map<String, Object> compare(BigDecimal current, BigDecimal previous) {
        BigDecimal change = current.subtract(previous);
        BigDecimal changePercent = previous.compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.ZERO
            : change.multiply(BigDecimal.valueOf(100)).divide(previous, 2, RoundingMode.HALF_UP);
        return Map.of("current", current, "previous", previous, "change", change, "changePercent", changePercent);
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

    private Scope scope(String authorization, Map<String, String> params) {
        User user = accessControl.requireUser(authorization);
        final Long companyId;
        try {
            companyId = optionalLong(params.get("companyId")).orElse(null);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "companyId must be a valid id");
        }
        Company company = accessControl.resolveCompany(user, companyId);
        return new Scope(user.id, company.id);
    }

    private YearMonth parseMonth(String value) {
        if (value == null || value.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(value);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must use yyyy-MM format");
        }
    }

    private YearMonth parseAnchorDate(String value) {
        if (value == null || value.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.from(LocalDate.parse(value));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endDate must use yyyy-MM-dd format");
        }
    }

    private record Scope(long userId, long companyId) {
    }
}
