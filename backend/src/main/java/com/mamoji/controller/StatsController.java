package com.mamoji.controller;

import com.mamoji.entity.Category;
import com.mamoji.entity.Transaction;
import com.mamoji.entity.User;
import com.mamoji.repository.AccountRepository;
import com.mamoji.repository.CategoryRepository;
import com.mamoji.repository.TransactionRepository;
import com.mamoji.security.AuthenticationUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class StatsController {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverview(
            @AuthenticationUser User user,
            @RequestParam(required = false, defaultValue = "") String month) {

        YearMonth yearMonth;
        if (month == null || month.isEmpty()) {
            yearMonth = YearMonth.now();
        } else {
            // Handle both YYYY-MM and YYYY-MM-DD formats
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

    @GetMapping("/trend")
    public ResponseEntity<Map<String, Object>> getTrend(
            @AuthenticationUser User user,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        // Default to current month if not provided
        YearMonth start;
        YearMonth end;
        if (startDate != null && !startDate.isEmpty() && endDate != null && !endDate.isEmpty()) {
            // Handle both YYYY-MM and YYYY-MM-DD formats
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

    private BigDecimal sumTypeByMonth(Long userId, int type, YearMonth month) {
        return safeAmount(transactionRepository.sumByUserIdAndTypeAndDateBetween(userId, type, month.atDay(1), month.atEndOfMonth()));
    }

    private List<Map<String, Object>> toCategoryItems(List<TransactionRepository.CategoryStatsProjection> projections) {
        return projections.stream().map(item -> {
            Map<String, Object> row = new HashMap<>();
            row.put("categoryId", item.getCategoryId());
            row.put("categoryName", item.getCategoryName());
            row.put("amount", item.getAmount());
            return row;
        }).toList();
    }

    private BigDecimal safeAmount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

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
