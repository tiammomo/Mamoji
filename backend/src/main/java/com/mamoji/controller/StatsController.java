package com.mamoji.controller;

import com.mamoji.entity.Category;
import com.mamoji.entity.Transaction;
import com.mamoji.entity.User;
import com.mamoji.repository.AccountRepository;
import com.mamoji.repository.BudgetRepository;
import com.mamoji.repository.CategoryRepository;
import com.mamoji.repository.TransactionRepository;
import com.mamoji.security.AuthenticationUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统计报表控制器。
 *
 * <p>负责将交易、账户、预算等数据聚合为前端报表所需结构，
 * 覆盖总览、趋势、分类、年度、对比和洞察等多个视角。
 */
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class StatsController {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;
    private final BudgetRepository budgetRepository;

    /**
     * 月度总览：输出收入、支出、结余等核心摘要。
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverview(
            @AuthenticationUser User user,
            @RequestParam(required = false, defaultValue = "") String month) {

        YearMonth yearMonth;
        if (month == null || month.isEmpty()) {
            yearMonth = YearMonth.now();
        } else {
            // 兼容 YYYY-MM 与 YYYY-MM-DD 两种输入格式。
            try {
                yearMonth = YearMonth.parse(month);
            } catch (Exception e) {
                yearMonth = YearMonth.from(LocalDate.parse(month));
            }
        }

        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.atEndOfMonth();

        BigDecimal income = transactionRepository.sumByUserIdAndTypeAndDateBetween(
            user.getId(), 1, startDate, endDate);
        BigDecimal expense = transactionRepository.sumByUserIdAndTypeAndDateBetween(
            user.getId(), 2, startDate, endDate);

        if (income == null) income = BigDecimal.ZERO;
        if (expense == null) expense = BigDecimal.ZERO;

        Map<String, Object> data = new HashMap<>();
        data.put("income", income);
        data.put("expense", expense);
        data.put("balance", income.subtract(expense));
        data.put("incomeCount", 0);
        data.put("expenseCount", 0);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", data);

        return ResponseEntity.ok(result);
    }

    /**
     * 趋势图数据：按月聚合收入与支出。
     */
    @GetMapping("/trend")
    public ResponseEntity<Map<String, Object>> getTrend(
            @AuthenticationUser User user,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        // 未传时间范围时默认返回当前月。
        YearMonth start;
        YearMonth end;
        if (startDate != null && !startDate.isEmpty() && endDate != null && !endDate.isEmpty()) {
            // 兼容 YYYY-MM 与 YYYY-MM-DD 两种输入格式。
            try {
                start = YearMonth.parse(startDate);
            } catch (Exception e) {
                start = YearMonth.from(java.time.LocalDate.parse(startDate));
            }
            try {
                end = YearMonth.parse(endDate);
            } catch (Exception e) {
                end = YearMonth.from(java.time.LocalDate.parse(endDate));
            }
        } else {
            YearMonth current = YearMonth.now();
            start = YearMonth.of(current.getYear(), current.getMonthValue());
            end = start;
        }

        List<Map<String, Object>> trend = new ArrayList<>();

        YearMonth current = start;
        while (!current.isAfter(end)) {
            LocalDate startD = current.atDay(1);
            LocalDate endD = current.atEndOfMonth();

            BigDecimal income = transactionRepository.sumByUserIdAndTypeAndDateBetween(
                user.getId(), 1, startD, endD);
            BigDecimal expense = transactionRepository.sumByUserIdAndTypeAndDateBetween(
                user.getId(), 2, startD, endD);

            if (income == null) income = BigDecimal.ZERO;
            if (expense == null) expense = BigDecimal.ZERO;

            Map<String, Object> monthData = new HashMap<>();
            monthData.put("month", current.toString());
            monthData.put("income", income);
            monthData.put("expense", expense);
            trend.add(monthData);

            current = current.plusMonths(1);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", trend);

        return ResponseEntity.ok(result);
    }

    /**
     * 分类统计：按类型（收入/支出）统计分类金额与占比。
     */
    @GetMapping("/categories")
    public ResponseEntity<Map<String, Object>> getCategoryStats(
            @AuthenticationUser User user,
            @RequestParam Integer type,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        LocalDate start;
        LocalDate end;
        if (startDate != null && !startDate.isEmpty() && endDate != null && !endDate.isEmpty()) {
            start = LocalDate.parse(startDate);
            end = LocalDate.parse(endDate);
        } else {
            YearMonth yearMonth = YearMonth.now();
            start = yearMonth.atDay(1);
            end = yearMonth.atEndOfMonth();
        }

        List<Object[]> results = transactionRepository.sumByCategoryAndType(
            user.getId(), type, start, end);

        Map<Long, Category> categoryMap = categoryRepository.findAll().stream()
            .collect(Collectors.toMap(Category::getId, c -> c));

        BigDecimal total = results.stream()
            .map(r -> (BigDecimal) r[1])
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Map<String, Object>> stats = new ArrayList<>();

        for (Object[] row : results) {
            Long categoryId = (Long) row[0];
            BigDecimal amount = (BigDecimal) row[1];

            Category category = categoryMap.get(categoryId);

            Map<String, Object> stat = new HashMap<>();
            stat.put("categoryId", categoryId);
            stat.put("categoryName", category != null ? category.getName() : "未知");
            stat.put("categoryIcon", category != null ? category.getIcon() : "category");
            stat.put("amount", amount);
            stat.put("percentage", total.compareTo(BigDecimal.ZERO) > 0
                ? amount.multiply(BigDecimal.valueOf(100)).divide(total, 1, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO);

            stats.add(stat);
        }

        stats.sort((a, b) -> ((BigDecimal) b.get("amount")).compareTo((BigDecimal) a.get("amount")));

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", stats);

        return ResponseEntity.ok(result);
    }

    /**
     * 年度报表：输出全年总览、月度拆解和分类分布。
     */
    @GetMapping("/annual")
    public ResponseEntity<Map<String, Object>> getAnnualReport(
            @AuthenticationUser User user,
            @RequestParam int year) {

        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);

        BigDecimal totalIncome = safeAmount(transactionRepository.sumByUserIdAndTypeAndDateBetween(user.getId(), 1, start, end));
        BigDecimal totalExpense = safeAmount(transactionRepository.sumByUserIdAndTypeAndDateBetween(user.getId(), 2, start, end));
        BigDecimal totalBalance = totalIncome.subtract(totalExpense);

        List<Map<String, Object>> monthlyData = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            YearMonth ym = YearMonth.of(year, month);
            BigDecimal income = safeAmount(transactionRepository.sumByUserIdAndTypeAndDateBetween(user.getId(), 1, ym.atDay(1), ym.atEndOfMonth()));
            BigDecimal expense = safeAmount(transactionRepository.sumByUserIdAndTypeAndDateBetween(user.getId(), 2, ym.atDay(1), ym.atEndOfMonth()));
            monthlyData.add(Map.of(
                "month", month,
                "income", income,
                "expense", expense,
                "balance", income.subtract(expense)
            ));
        }

        List<Map<String, Object>> incomeByCategory = toCategoryItems(
            transactionRepository.sumByCategoryAndTypeWithCategoryName(user.getId(), 1, start, end)
        );
        List<Map<String, Object>> expenseByCategory = toCategoryItems(
            transactionRepository.sumByCategoryAndTypeWithCategoryName(user.getId(), 2, start, end)
        );

        Map<String, Object> data = new HashMap<>();
        data.put("year", year);
        data.put("totalIncome", totalIncome);
        data.put("totalExpense", totalExpense);
        data.put("totalBalance", totalBalance);
        data.put("monthlyData", monthlyData);
        data.put("incomeByCategory", incomeByCategory);
        data.put("expenseByCategory", expenseByCategory);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", data);
        return ResponseEntity.ok(result);
    }

    /**
     * 资产负债摘要：月度与年度收支、净资产、总资产、总负债。
     */
    @GetMapping("/balance-sheet")
    public ResponseEntity<Map<String, Object>> getBalanceSheet(@AuthenticationUser User user) {
        YearMonth current = YearMonth.now();

        BigDecimal monthlyIncome = safeAmount(transactionRepository.sumByUserIdAndTypeAndDateBetween(
            user.getId(), 1, current.atDay(1), current.atEndOfMonth()));
        BigDecimal monthlyExpense = safeAmount(transactionRepository.sumByUserIdAndTypeAndDateBetween(
            user.getId(), 2, current.atDay(1), current.atEndOfMonth()));
        BigDecimal monthlyBalance = monthlyIncome.subtract(monthlyExpense);

        LocalDate yearStart = LocalDate.of(current.getYear(), 1, 1);
        LocalDate yearEnd = LocalDate.of(current.getYear(), 12, 31);
        BigDecimal yearlyIncome = safeAmount(transactionRepository.sumByUserIdAndTypeAndDateBetween(
            user.getId(), 1, yearStart, yearEnd));
        BigDecimal yearlyExpense = safeAmount(transactionRepository.sumByUserIdAndTypeAndDateBetween(
            user.getId(), 2, yearStart, yearEnd));
        BigDecimal yearlyBalance = yearlyIncome.subtract(yearlyExpense);

        BigDecimal totalAssets = safeAmount(accountRepository.getTotalAssets(user.getId()));
        BigDecimal totalLiabilities = safeAmount(accountRepository.getTotalLiabilities(user.getId()));
        BigDecimal netWorth = totalAssets.subtract(totalLiabilities);

        Map<String, Object> data = new HashMap<>();
        data.put("monthlyIncome", monthlyIncome);
        data.put("monthlyExpense", monthlyExpense);
        data.put("monthlyBalance", monthlyBalance);
        data.put("yearlyIncome", yearlyIncome);
        data.put("yearlyExpense", yearlyExpense);
        data.put("yearlyBalance", yearlyBalance);
        data.put("netWorth", netWorth);
        data.put("totalAssets", totalAssets);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", data);
        return ResponseEntity.ok(result);
    }

    /**
     * 环比/同比对比报表。
     */
    @GetMapping("/comparison")
    public ResponseEntity<Map<String, Object>> getComparison(
            @AuthenticationUser User user,
            @RequestParam(required = false) String month) {

        YearMonth currentMonth;
        if (month == null || month.isBlank()) {
            currentMonth = YearMonth.now();
        } else {
            try {
                currentMonth = YearMonth.parse(month);
            } catch (Exception ex) {
                currentMonth = YearMonth.from(LocalDate.parse(month));
            }
        }

        YearMonth previousMonth = currentMonth.minusMonths(1);
        YearMonth sameMonthLastYear = currentMonth.minusYears(1);

        BigDecimal currentIncome = sumTypeByMonth(user.getId(), 1, currentMonth);
        BigDecimal currentExpense = sumTypeByMonth(user.getId(), 2, currentMonth);
        BigDecimal currentBalance = currentIncome.subtract(currentExpense);

        BigDecimal previousIncome = sumTypeByMonth(user.getId(), 1, previousMonth);
        BigDecimal previousExpense = sumTypeByMonth(user.getId(), 2, previousMonth);
        BigDecimal previousBalance = previousIncome.subtract(previousExpense);

        BigDecimal yearlyIncome = sumTypeByMonth(user.getId(), 1, sameMonthLastYear);
        BigDecimal yearlyExpense = sumTypeByMonth(user.getId(), 2, sameMonthLastYear);
        BigDecimal yearlyBalance = yearlyIncome.subtract(yearlyExpense);

        Map<String, Object> data = new HashMap<>();
        data.put("currentMonth", currentMonth.toString());
        data.put("currentIncome", currentIncome);
        data.put("currentExpense", currentExpense);
        data.put("currentBalance", currentBalance);
        data.put("previousMonth", previousMonth.toString());
        data.put("previousIncome", previousIncome);
        data.put("previousExpense", previousExpense);
        data.put("sameMonthLastYear", sameMonthLastYear.toString());
        data.put("yearlyIncome", yearlyIncome);
        data.put("yearlyExpense", yearlyExpense);
        data.put("monthOverMonth", Map.of(
            "incomeChange", pctChange(currentIncome, previousIncome),
            "expenseChange", pctChange(currentExpense, previousExpense),
            "balanceChange", pctChange(currentBalance, previousBalance)
        ));
        data.put("yearOverYear", Map.of(
            "incomeChange", pctChange(currentIncome, yearlyIncome),
            "expenseChange", pctChange(currentExpense, yearlyExpense),
            "balanceChange", pctChange(currentBalance, yearlyBalance)
        ));

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", data);
        return ResponseEntity.ok(result);
    }

    /**
     * 高级洞察：最近/大额收支、异常分类、预算告警与摘要。
     */
    @GetMapping("/insights")
    public ResponseEntity<Map<String, Object>> getInsights(
        @AuthenticationUser User user,
        @RequestParam(required = false) String month
    ) {
        YearMonth currentMonth = parseYearMonth(month);
        YearMonth previousMonth = currentMonth.minusMonths(1);
        LocalDate start = currentMonth.atDay(1);
        LocalDate end = currentMonth.atEndOfMonth();
        LocalDate previousStart = previousMonth.atDay(1);
        LocalDate previousEnd = previousMonth.atEndOfMonth();

        Map<Long, Category> categoryMap = categoryRepository.findAll().stream()
            .collect(Collectors.toMap(Category::getId, item -> item));

        List<Transaction> largestExpenses = transactionRepository.findTopByUserIdAndTypeAndDateBetweenOrderByAmountDesc(
            user.getId(),
            2,
            start,
            end,
            PageRequest.of(0, 5)
        );
        List<Transaction> largestIncomes = transactionRepository.findTopByUserIdAndTypeAndDateBetweenOrderByAmountDesc(
            user.getId(),
            1,
            start,
            end,
            PageRequest.of(0, 5)
        );
        List<Transaction> recentExpenses = transactionRepository.findTopByUserIdAndTypeAndDateBetweenOrderByDateDesc(
            user.getId(),
            2,
            start,
            end,
            PageRequest.of(0, 5)
        );
        List<Transaction> recentIncomes = transactionRepository.findTopByUserIdAndTypeAndDateBetweenOrderByDateDesc(
            user.getId(),
            1,
            start,
            end,
            PageRequest.of(0, 5)
        );

        List<Map<String, Object>> expenseAnomalies = buildExpenseAnomalies(
            user.getId(),
            start,
            end,
            previousStart,
            previousEnd,
            categoryMap
        );
        List<Map<String, Object>> budgetAlerts = buildBudgetAlerts(user.getId(), start, end, categoryMap);

        Map<String, Object> summary = new HashMap<>();
        summary.put("largestExpenseAmount", largestExpenses.isEmpty() ? BigDecimal.ZERO : safeAmount(largestExpenses.get(0).getAmount()));
        summary.put("largestIncomeAmount", largestIncomes.isEmpty() ? BigDecimal.ZERO : safeAmount(largestIncomes.get(0).getAmount()));
        summary.put("anomalyCount", expenseAnomalies.size());
        summary.put("budgetAlertCount", budgetAlerts.size());

        Map<String, Object> data = new HashMap<>();
        data.put("month", currentMonth.toString());
        data.put("largestExpenses", largestExpenses.stream().map(item -> toTransactionItem(item, categoryMap)).toList());
        data.put("largestIncomes", largestIncomes.stream().map(item -> toTransactionItem(item, categoryMap)).toList());
        data.put("recentExpenses", recentExpenses.stream().map(item -> toTransactionItem(item, categoryMap)).toList());
        data.put("recentIncomes", recentIncomes.stream().map(item -> toTransactionItem(item, categoryMap)).toList());
        data.put("expenseAnomalies", expenseAnomalies);
        data.put("budgetAlerts", budgetAlerts);
        data.put("summary", summary);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", data);
        return ResponseEntity.ok(result);
    }

    /**
     * 按月汇总指定收支类型金额。
     */
    private BigDecimal sumTypeByMonth(Long userId, int type, YearMonth month) {
        return safeAmount(transactionRepository.sumByUserIdAndTypeAndDateBetween(userId, type, month.atDay(1), month.atEndOfMonth()));
    }

    /**
     * 识别支出异常分类：
     * 以“金额提升 + 增长比例”双阈值过滤，输出 topN 异常项。
     */
    private List<Map<String, Object>> buildExpenseAnomalies(
        Long userId,
        LocalDate currentStart,
        LocalDate currentEnd,
        LocalDate previousStart,
        LocalDate previousEnd,
        Map<Long, Category> categoryMap
    ) {
        List<Object[]> currentRows = transactionRepository.sumByCategoryAndType(userId, 2, currentStart, currentEnd);
        List<Object[]> previousRows = transactionRepository.sumByCategoryAndType(userId, 2, previousStart, previousEnd);

        Map<Long, BigDecimal> previousByCategory = new HashMap<>();
        for (Object[] row : previousRows) {
            Long categoryId = (Long) row[0];
            previousByCategory.put(categoryId, safeAmount((BigDecimal) row[1]));
        }

        List<Map<String, Object>> anomalies = new ArrayList<>();
        for (Object[] row : currentRows) {
            Long categoryId = (Long) row[0];
            BigDecimal currentAmount = safeAmount((BigDecimal) row[1]);
            BigDecimal previousAmount = safeAmount(previousByCategory.get(categoryId));
            BigDecimal changeAmount = currentAmount.subtract(previousAmount);
            double changePercent = pctChange(currentAmount, previousAmount);

            if (currentAmount.compareTo(new BigDecimal("100")) < 0) {
                continue;
            }
            if (changeAmount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (changePercent < 30.0D) {
                continue;
            }

            Map<String, Object> item = new HashMap<>();
            item.put("categoryId", categoryId);
            item.put("categoryName", resolveCategoryName(categoryId, categoryMap));
            item.put("currentAmount", currentAmount);
            item.put("previousAmount", previousAmount);
            item.put("changeAmount", changeAmount);
            item.put("changePercent", BigDecimal.valueOf(changePercent).setScale(1, RoundingMode.HALF_UP));
            anomalies.add(item);
        }

        anomalies.sort((left, right) -> ((BigDecimal) right.get("changeAmount")).compareTo((BigDecimal) left.get("changeAmount")));
        if (anomalies.size() > 5) {
            return anomalies.subList(0, 5);
        }
        return anomalies;
    }

    /**
     * 构建预算告警列表，识别 warning 与 over 两类状态。
     */
    private List<Map<String, Object>> buildBudgetAlerts(
        Long userId,
        LocalDate currentStart,
        LocalDate currentEnd,
        Map<Long, Category> categoryMap
    ) {
        List<Map<String, Object>> alerts = new ArrayList<>();

        budgetRepository.findByUserIdAndStatus(userId, 1).forEach(budget -> {
            LocalDate periodStart = maxDate(budget.getStartDate(), currentStart);
            LocalDate periodEnd = minDate(budget.getEndDate(), currentEnd);
            if (periodStart.isAfter(periodEnd)) {
                return;
            }

            BigDecimal spent = budget.getCategoryId() == null
                ? safeAmount(transactionRepository.sumByUserIdAndTypeAndDateBetween(userId, 2, periodStart, periodEnd))
                : safeAmount(transactionRepository.sumByUserIdAndTypeAndCategoryIdAndDateBetween(
                    userId,
                    2,
                    budget.getCategoryId(),
                    periodStart,
                    periodEnd
                ));

            BigDecimal amount = safeAmount(budget.getAmount());
            BigDecimal usageRate = amount.compareTo(BigDecimal.ZERO) > 0
                ? spent.multiply(BigDecimal.valueOf(100)).divide(amount, 1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            int warningThreshold = budget.getWarningThreshold() == null ? 85 : budget.getWarningThreshold();

            String status = "normal";
            if (usageRate.compareTo(BigDecimal.valueOf(100)) >= 0) {
                status = "over";
            } else if (usageRate.compareTo(BigDecimal.valueOf(warningThreshold)) >= 0) {
                status = "warning";
            }

            if ("normal".equals(status)) {
                return;
            }

            Map<String, Object> item = new HashMap<>();
            item.put("budgetId", budget.getId());
            item.put("budgetName", budget.getName());
            item.put("amount", amount);
            item.put("spent", spent);
            item.put("usageRate", usageRate);
            item.put("warningThreshold", warningThreshold);
            item.put("status", status);
            item.put("categoryId", budget.getCategoryId());
            item.put("categoryName", resolveCategoryName(budget.getCategoryId(), categoryMap));
            alerts.add(item);
        });

        alerts.sort((left, right) -> ((BigDecimal) right.get("usageRate")).compareTo((BigDecimal) left.get("usageRate")));
        if (alerts.size() > 5) {
            return alerts.subList(0, 5);
        }
        return alerts;
    }

    /**
     * 交易行转换为展示对象，补齐类别名称等前端字段。
     */
    private Map<String, Object> toTransactionItem(Transaction transaction, Map<Long, Category> categoryMap) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", transaction.getId());
        item.put("type", transaction.getType());
        item.put("amount", safeAmount(transaction.getAmount()));
        item.put("date", transaction.getDate());
        item.put("remark", transaction.getRemark() == null ? "" : transaction.getRemark());
        item.put("categoryId", transaction.getCategoryId());
        item.put("categoryName", resolveCategoryName(transaction.getCategoryId(), categoryMap));
        return item;
    }

    /**
     * 将分类聚合投影转换为统一报表项结构。
     */
    private List<Map<String, Object>> toCategoryItems(List<TransactionRepository.CategoryStatsProjection> projections) {
        return projections.stream().map(item -> {
            Map<String, Object> row = new HashMap<>();
            row.put("categoryId", item.getCategoryId());
            row.put("categoryName", item.getCategoryName());
            row.put("amount", item.getAmount());
            return row;
        }).toList();
    }

    /**
     * 将可空金额归一化为零值，避免后续计算出现空指针。
     */
    private BigDecimal safeAmount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 解析分类名称；当分类缺失或已删除时统一回退到“未分类”。
     */
    private String resolveCategoryName(Long categoryId, Map<Long, Category> categoryMap) {
        if (categoryId == null) {
            return "未分类";
        }
        Category category = categoryMap.get(categoryId);
        return category == null ? "未分类" : category.getName();
    }

    /**
     * 返回两个日期中较晚的一个。
     */
    private LocalDate maxDate(LocalDate left, LocalDate right) {
        return left.isAfter(right) ? left : right;
    }

    /**
     * 返回两个日期中较早的一个。
     */
    private LocalDate minDate(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    /**
     * 兼容 YYYY-MM 与 YYYY-MM-DD 两种月份输入格式。
     */
    private YearMonth parseYearMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(month);
        } catch (Exception ex) {
            return YearMonth.from(LocalDate.parse(month));
        }
    }

    /**
     * 计算百分比变化率；上一期为零时返回 0 或 100 作为保守兜底。
     */
    private double pctChange(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return current.compareTo(BigDecimal.ZERO) == 0 ? 0D : 100D;
        }
        BigDecimal delta = current.subtract(previous);
        return delta.multiply(BigDecimal.valueOf(100))
            .divide(previous.abs(), 1, java.math.RoundingMode.HALF_UP)
            .doubleValue();
    }
}
