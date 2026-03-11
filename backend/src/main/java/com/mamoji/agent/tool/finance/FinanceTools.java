package com.mamoji.agent.tool.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.agent.tool.BaseTool;
import com.mamoji.entity.Budget;
import com.mamoji.repository.BudgetRepository;
import com.mamoji.repository.CategoryRepository;
import com.mamoji.repository.TransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Finance toolset providing structured JSON data for agent/tool calls.
 */
@Slf4j
@Component
public class FinanceTools extends BaseTool {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final BudgetRepository budgetRepository;

    public FinanceTools(
        ObjectMapper objectMapper,
        TransactionRepository transactionRepository,
        CategoryRepository categoryRepository,
        BudgetRepository budgetRepository
    ) {
        super(objectMapper);
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.budgetRepository = budgetRepository;
    }

    /**
     * Returns income/expense summary for given period.
     */
    public String queryIncomeExpense(Long userId, String startDate, String endDate) {
        if (userId == null) {
            return buildError("userId is required");
        }

        try {
            YearMonth currentMonth = YearMonth.now();
            LocalDate start = startDate != null ? LocalDate.parse(startDate) : currentMonth.atDay(1);
            LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

            BigDecimal totalIncome = transactionRepository.sumByUserIdAndTypeAndDateBetween(userId, 1, start, end);
            totalIncome = totalIncome != null ? totalIncome : BigDecimal.ZERO;

            BigDecimal totalExpense = transactionRepository.sumByUserIdAndTypeAndDateBetween(userId, 2, start, end);
            totalExpense = totalExpense != null ? totalExpense : BigDecimal.ZERO;

            BigDecimal balance = totalIncome.subtract(totalExpense);

            long incomeCount = transactionRepository.countByUserIdAndTypeAndDateBetween(userId, 1, start, end);
            long expenseCount = transactionRepository.countByUserIdAndTypeAndDateBetween(userId, 2, start, end);

            Map<String, Object> result = new HashMap<>();
            result.put("period", start + " to " + end);
            result.put("totalIncome", totalIncome);
            result.put("totalExpense", totalExpense);
            result.put("balance", balance);
            result.put("incomeCount", incomeCount);
            result.put("expenseCount", expenseCount);
            return toJson(result);
        } catch (Exception e) {
            return handleError(e, "query_income_expense");
        }
    }

    /**
     * Returns transaction list with optional filters.
     */
    public String queryTransactions(Long userId, String startDate, String endDate, Long categoryId, Integer type) {
        if (userId == null) {
            return buildError("userId is required");
        }

        try {
            LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().minusMonths(1);
            LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

            List<Map<String, Object>> transactions = transactionRepository
                .findByUserIdAndDateBetweenWithFilters(userId, start, end, categoryId, type)
                .stream()
                .map(tx -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("date", tx.getDate());
                    item.put("type", tx.getType() == 1 ? "income" : "expense");
                    item.put("categoryId", tx.getCategoryId());
                    categoryRepository.findById(tx.getCategoryId()).ifPresent(c -> item.put("category", c.getName()));
                    item.put("amount", tx.getAmount());
                    item.put("remark", tx.getRemark());
                    return item;
                })
                .collect(Collectors.toList());

            Map<String, Object> result = new HashMap<>();
            result.put("count", transactions.size());
            result.put("transactions", transactions);
            return toJson(result);
        } catch (Exception e) {
            return handleError(e, "query_transactions");
        }
    }

    /**
     * Returns budget status and usage metrics.
     */
    public String queryBudget(Long userId, Long budgetId) {
        if (userId == null) {
            return buildError("userId is required");
        }

        try {
            LocalDate today = LocalDate.now();
            Budget selectedBudget = resolveBudget(userId, budgetId, today);
            if (selectedBudget == null) {
                return toJson(Map.of(
                    "status", "no_active_budget",
                    "budgetId", budgetId,
                    "budgetAmount", BigDecimal.ZERO,
                    "spent", BigDecimal.ZERO,
                    "remaining", BigDecimal.ZERO,
                    "usageRate", 0.0
                ));
            }

            BigDecimal budgetAmount = selectedBudget.getAmount() != null ? selectedBudget.getAmount() : BigDecimal.ZERO;
            BigDecimal spent = resolveSpent(userId, selectedBudget);
            BigDecimal remaining = budgetAmount.subtract(spent);
            double usageRate = budgetAmount.compareTo(BigDecimal.ZERO) > 0
                ? spent.multiply(new BigDecimal("100")).divide(budgetAmount, 2, RoundingMode.HALF_UP).doubleValue()
                : 0;

            int warningThreshold = selectedBudget.getWarningThreshold() != null ? selectedBudget.getWarningThreshold() : 80;
            String status = usageRate >= 100 ? "over" : (usageRate >= warningThreshold ? "warning" : "normal");

            Map<String, Object> result = new HashMap<>();
            result.put("name", selectedBudget.getName());
            result.put("budgetId", selectedBudget.getId());
            result.put("categoryId", selectedBudget.getCategoryId());
            result.put("warningThreshold", warningThreshold);
            result.put("budgetAmount", budgetAmount);
            result.put("spent", spent);
            result.put("remaining", remaining);
            result.put("usageRate", usageRate);
            result.put("status", status);
            result.put("period", selectedBudget.getStartDate() + " to " + selectedBudget.getEndDate());
            return toJson(result);
        } catch (Exception e) {
            return handleError(e, "query_budget");
        }
    }

    /**
     * Returns category-level amount and percentage statistics.
     */
    public String queryCategoryStats(Long userId, String startDate, String endDate, Integer type) {
        if (userId == null) {
            return buildError("userId is required");
        }

        try {
            YearMonth currentMonth = YearMonth.now();
            LocalDate start = startDate != null ? LocalDate.parse(startDate) : currentMonth.atDay(1);
            LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();
            int queryType = type != null ? type : 2;

            BigDecimal totalExpense = transactionRepository.sumByUserIdAndTypeAndDateBetween(userId, queryType, start, end);
            final BigDecimal total = totalExpense != null ? totalExpense : BigDecimal.ZERO;

            List<Map<String, Object>> categories = transactionRepository
                .sumByCategoryAndTypeWithCategoryName(userId, queryType, start, end)
                .stream()
                .map(row -> {
                    Map<String, Object> item = new HashMap<>();
                    Long categoryId = row.getCategoryId();
                    BigDecimal amount = row.getAmount();
                    item.put("categoryId", categoryId);
                    item.put("categoryName", row.getCategoryName());
                    item.put("amount", amount);
                    item.put("percentage", total.compareTo(BigDecimal.ZERO) > 0
                        ? amount.multiply(new BigDecimal("100")).divide(total, 2, RoundingMode.HALF_UP).doubleValue()
                        : 0);
                    return item;
                })
                .sorted((a, b) -> ((BigDecimal) b.get("amount")).compareTo((BigDecimal) a.get("amount")))
                .collect(Collectors.toList());

            Map<String, Object> result = new HashMap<>();
            result.put("period", start + " to " + end);
            result.put("total", total);
            result.put("categories", categories);
            return toJson(result);
        } catch (Exception e) {
            return handleError(e, "query_category_stats");
        }
    }

    /**
     * Resolves requested budget or best active fallback budget.
     */
    private Budget resolveBudget(Long userId, Long budgetId, LocalDate today) {
        if (budgetId != null) {
            return budgetRepository.findByIdAndUserId(budgetId, userId).orElse(null);
        }

        Budget totalBudget = budgetRepository.findActiveBudgetWithoutCategory(userId, today).orElse(null);
        if (totalBudget != null) {
            return totalBudget;
        }

        List<Budget> activeBudgets = budgetRepository.findActiveBudgets(userId, today);
        return activeBudgets.isEmpty() ? null : activeBudgets.get(0);
    }

    /**
     * Calculates spent amount for budget range/category.
     */
    private BigDecimal resolveSpent(Long userId, Budget budget) {
        if (budget.getStartDate() == null || budget.getEndDate() == null) {
            return BigDecimal.ZERO;
        }
        if (budget.getCategoryId() == null) {
            BigDecimal spent = transactionRepository.sumByUserIdAndTypeAndDateBetween(userId, 2, budget.getStartDate(), budget.getEndDate());
            return spent != null ? spent : BigDecimal.ZERO;
        }
        BigDecimal spent = transactionRepository.sumByUserIdAndTypeAndCategoryIdAndDateBetween(
            userId,
            2,
            budget.getCategoryId(),
            budget.getStartDate(),
            budget.getEndDate()
        );
        return spent != null ? spent : BigDecimal.ZERO;
    }
}
