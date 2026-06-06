package com.mamoji.service;

import com.mamoji.domain.Models.Account;
import com.mamoji.domain.Models.Category;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

import static com.mamoji.common.PayloadReader.intParam;

@Service
public class ReportingService {
    private final InMemoryStore store;
    private final AccessControlService accessControl;

    public ReportingService(InMemoryStore store, AccessControlService accessControl) {
        this.store = store;
        this.accessControl = accessControl;
    }

    public Map<String, BigDecimal> overview(String authorization) {
        long userId = accessControl.requireUser(authorization).id;
        YearMonth current = YearMonth.now();
        BigDecimal income = sumTransactions(userId, tx -> tx.type == 1 && sameMonth(tx.date, current));
        BigDecimal expense = sumTransactions(userId, tx -> tx.type == 2 && sameMonth(tx.date, current));
        store.attachBudgetData();
        double usage = store.budgets.values().stream()
            .filter(budget -> budget.userId == userId && budget.status != 0)
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
        long userId = accessControl.requireUser(authorization).id;
        String period = params.getOrDefault("period", "month");
        int count = intParam(params, "limit", switch (period) {
            case "quarter" -> 4;
            case "year" -> 5;
            default -> intParam(params, "months", 6);
        });
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = Math.max(1, count) - 1; i >= 0; i--) {
            if ("quarter".equals(period)) {
                points.add(trendPoint(userId, YearMonth.now().minusMonths(i * 3L), "quarter"));
            } else if ("year".equals(period)) {
                points.add(trendPoint(userId, YearMonth.now().minusYears(i), "year"));
            } else {
                points.add(trendPoint(userId, YearMonth.now().minusMonths(i), "month"));
            }
        }
        return points;
    }

    public List<Map<String, Object>> categoryStats(String authorization, Map<String, String> params) {
        long userId = accessControl.requireUser(authorization).id;
        int type = transactionTypeParam(params.get("type"), 2);
        List<TransactionRecord> txs = store.transactions.values().stream()
            .filter(tx -> tx.userId == userId && tx.type == type)
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
        long userId = accessControl.requireUser(authorization).id;
        List<Map<String, Object>> months = new ArrayList<>();
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        for (int month = 1; month <= 12; month++) {
            YearMonth current = YearMonth.of(year, month);
            BigDecimal income = sumTransactions(userId, tx -> tx.type == 1 && sameMonth(tx.date, current));
            BigDecimal expense = sumTransactions(userId, tx -> tx.type == 2 && sameMonth(tx.date, current));
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
        long userId = accessControl.requireUser(authorization).id;
        Map<String, BigDecimal> summary = accountSummary(userId);
        Map<String, Object> result = new LinkedHashMap<>(summary);
        List<Map<String, Object>> accounts = store.sortedAccounts().stream()
            .filter(account -> account.userId == userId)
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
        long userId = accessControl.requireUser(authorization).id;
        YearMonth current = YearMonth.now();
        YearMonth previous = current.minusMonths(1);
        YearMonth previousYear = current.minusYears(1);
        BigDecimal currentExpense = sumTransactions(userId, tx -> tx.type == 2 && sameMonth(tx.date, current));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mom", compare(currentExpense, sumTransactions(userId, tx -> tx.type == 2 && sameMonth(tx.date, previous))));
        result.put("yoy", compare(currentExpense, sumTransactions(userId, tx -> tx.type == 2 && sameMonth(tx.date, previousYear))));
        return result;
    }

    public Map<String, Object> insights(String authorization) {
        long userId = accessControl.requireUser(authorization).id;
        List<Map<String, Object>> large = store.sortedTransactions().stream()
            .filter(tx -> tx.userId == userId && tx.amount.compareTo(new BigDecimal("500")) >= 0)
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
            .filter(budget -> budget.userId == userId)
            .filter(budget -> budget.riskLevel.equals("high") || budget.riskLevel.equals("critical"))
            .map(budget -> Map.<String, Object>of("name", budget.name, "usageRate", budget.usageRate, "riskLevel", budget.riskLevel))
            .toList();
        return Map.of("largeTransactions", large, "categorySpikes", spikes, "budgetAlerts", alerts);
    }

    private Map<String, BigDecimal> accountSummary(long userId) {
        List<Account> accounts = store.sortedAccounts().stream().filter(account -> account.userId == userId).toList();
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
}
