package com.mamoji.service;

import com.mamoji.ai.AiGateway;
import com.mamoji.dto.AIChatResponse;
import com.mamoji.entity.Budget;
import com.mamoji.entity.Category;
import com.mamoji.entity.Transaction;
import com.mamoji.repository.BudgetRepository;
import com.mamoji.repository.CategoryRepository;
import com.mamoji.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AIService {

    private static final String FINANCE_SYSTEM_PROMPT = "You are a professional family finance assistant. " +
        "Help users analyze income/expense, create budgets, and answer finance-related questions. " +
        "Reply in Chinese, be friendly, professional and concise. " +
        "Only answer finance-related questions.";

    private static final String STOCK_SYSTEM_PROMPT = "You are a professional stock market analyst assistant. " +
        "Help users analyze stock trends, provide investment suggestions, and answer stock-related questions. " +
        "Reply in Chinese and always remind users that stock investment carries risks.";

    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final CategoryRepository categoryRepository;
    private final WebClient.Builder webClientBuilder;
    private final AiGateway aiGateway;

    public AIChatResponse chat(Long userId, String message, String assistantType) {
        String type = (assistantType == null || assistantType.isBlank()) ? "finance" : assistantType;

        String prompt;
        String systemPrompt;
        if ("stock".equals(type)) {
            String stockData = fetchStockData(message);
            prompt = buildStockPrompt(message, stockData);
            systemPrompt = STOCK_SYSTEM_PROMPT;
        } else {
            Map<String, Object> context = buildFinanceContext(userId);
            prompt = buildFinancePrompt(message, context);
            systemPrompt = FINANCE_SYSTEM_PROMPT;
        }

        return new AIChatResponse(aiGateway.chat(systemPrompt, prompt, null, type));
    }

    private String fetchStockData(String message) {
        StringBuilder stockData = new StringBuilder();
        List<String> stockCodes = extractStockCodes(message);

        if (stockCodes.isEmpty()) {
            stockCodes.add("sh000001");
            stockCodes.add("sz399001");
        }

        for (String code : stockCodes) {
            try {
                String data = fetchStockQuote(code);
                if (data != null) {
                    stockData.append(data).append("\n");
                }
            } catch (Exception e) {
                log.error("fetch stock data failed: {}", code, e);
            }
        }

        return stockData.toString();
    }

    private List<String> extractStockCodes(String message) {
        List<String> codes = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\b(\\d{6})\\b").matcher(message);
        while (matcher.find()) {
            String code = matcher.group(1);
            if (code.startsWith("6")) {
                codes.add("sh" + code);
            } else if (code.startsWith("0") || code.startsWith("3")) {
                codes.add("sz" + code);
            }
        }
        return codes;
    }

    private String fetchStockQuote(String stockCode) {
        String response = webClientBuilder
            .baseUrl("https://hq.sinajs.cn/list=" + stockCode)
            .defaultHeader("Referer", "https://finance.sina.com.cn/")
            .build()
            .get()
            .retrieve()
            .bodyToMono(String.class)
            .block();

        if (response == null || !response.contains("=")) {
            return null;
        }

        String data = response.substring(response.indexOf('=') + 1).replace("\"", "").replace(";", "");
        String[] parts = data.split(",");
        if (parts.length < 9) {
            return null;
        }

        return String.format("%s: current=%s open=%s close=%s high=%s low=%s volume=%s",
            parts[0], parts[3], parts[1], parts[2], parts[4], parts[5], parts[8]);
    }

    private Map<String, Object> buildFinanceContext(Long userId) {
        Map<String, Object> context = new HashMap<>();
        YearMonth currentMonth = YearMonth.now();
        LocalDate startDate = currentMonth.atDay(1);
        LocalDate endDate = currentMonth.atEndOfMonth();

        BigDecimal totalIncome = transactionRepository.sumByUserIdAndTypeAndDateBetween(userId, 1, startDate, endDate);
        BigDecimal totalExpense = transactionRepository.sumByUserIdAndTypeAndDateBetween(userId, 2, startDate, endDate);

        context.put("totalIncome", totalIncome != null ? totalIncome : BigDecimal.ZERO);
        context.put("totalExpense", totalExpense != null ? totalExpense : BigDecimal.ZERO);

        List<Transaction> recentTransactions = transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate);
        context.put("recentTransactions", recentTransactions.stream().limit(10).map(this::formatTransaction).toList());

        List<Object[]> categoryExpenses = transactionRepository.sumByCategoryAndType(userId, 2, startDate, endDate);
        context.put("categoryExpenses", formatCategoryExpenses(categoryExpenses));

        List<Budget> activeBudgets = budgetRepository.findActiveBudgets(userId, LocalDate.now());
        context.put("activeBudgets", activeBudgets.stream().map(this::formatBudget).toList());
        return context;
    }

    private String formatTransaction(Transaction transaction) {
        String categoryName = categoryRepository.findById(transaction.getCategoryId())
            .map(Category::getName)
            .orElse("Unknown");

        return String.format("%s | %s | %s | %.2f | %s",
            transaction.getDate(),
            transaction.getType() == 1 ? "income" : "expense",
            categoryName,
            transaction.getAmount(),
            transaction.getRemark() != null ? transaction.getRemark() : "");
    }

    private Map<String, Object> formatBudget(Budget budget) {
        Map<String, Object> item = new HashMap<>();
        item.put("startDate", budget.getStartDate());
        item.put("endDate", budget.getEndDate());
        item.put("amount", budget.getAmount());
        item.put("categoryId", budget.getCategoryId());

        if (budget.getCategoryId() != null) {
            categoryRepository.findById(budget.getCategoryId()).ifPresent(c -> item.put("categoryName", c.getName()));
        } else {
            item.put("categoryName", "total");
        }
        return item;
    }

    private List<Map<String, Object>> formatCategoryExpenses(List<Object[]> categoryExpenses) {
        return categoryExpenses.stream().map(row -> {
            Long categoryId = (Long) row[0];
            BigDecimal amount = (BigDecimal) row[1];

            Map<String, Object> item = new HashMap<>();
            item.put("amount", amount);
            item.put("categoryName", categoryRepository.findById(categoryId).map(Category::getName).orElse("Unknown"));
            return item;
        }).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private String buildFinancePrompt(String message, Map<String, Object> context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("User question: ").append(message).append("\n\n");
        prompt.append("=== Financial Data ===\n");
        prompt.append(String.format("Monthly income: %.2f\n", context.get("totalIncome")));
        prompt.append(String.format("Monthly expense: %.2f\n", context.get("totalExpense")));

        List<Map<String, Object>> categoryExpenses = (List<Map<String, Object>>) context.get("categoryExpenses");
        if (categoryExpenses != null && !categoryExpenses.isEmpty()) {
            prompt.append("Category expenses:\n");
            for (Map<String, Object> cat : categoryExpenses) {
                prompt.append(String.format("- %s: %.2f\n", cat.get("categoryName"), cat.get("amount")));
            }
        }

        List<Map<String, Object>> activeBudgets = (List<Map<String, Object>>) context.get("activeBudgets");
        if (activeBudgets != null && !activeBudgets.isEmpty()) {
            prompt.append("Active budgets:\n");
            for (Map<String, Object> budget : activeBudgets) {
                prompt.append(String.format("- %s: %.2f (%s to %s)\n",
                    budget.get("categoryName"), budget.get("amount"), budget.get("startDate"), budget.get("endDate")));
            }
        }

        List<String> recentTransactions = (List<String>) context.get("recentTransactions");
        if (recentTransactions != null && !recentTransactions.isEmpty()) {
            prompt.append("Recent transactions:\n");
            for (String tx : recentTransactions) {
                prompt.append("- ").append(tx).append("\n");
            }
        }

        return prompt.toString();
    }

    private String buildStockPrompt(String message, String stockData) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("User question: ").append(message).append("\n\n");
        if (stockData != null && !stockData.isBlank()) {
            prompt.append("=== Market Data ===\n").append(stockData);
        } else {
            prompt.append("No real-time stock data available.\n");
        }
        return prompt.toString();
    }
}
