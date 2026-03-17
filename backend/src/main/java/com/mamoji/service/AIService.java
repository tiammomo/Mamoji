package com.mamoji.service;

import com.mamoji.ai.AiGateway;
import com.mamoji.ai.intent.FinanceIntentClassifier;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared AI chat service for finance and stock assistants.
 *
 * <p>This class is responsible for: contextual data preparation, prompt assembly,
 * gateway invocation, and robust fallback replies when model output is missing or malformed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIService {

    private static final String OUTPUT_STYLE = """
        请使用简体中文，并严格按以下结构输出：
        结论：<一句话>
        关键数据：
        - 指标：数值（含时间口径）
        建议：
        - 可执行动作
        控制在 6~12 行，不要长段落，不要编造数据。
        """;

    private static final String FINANCE_SYSTEM_PROMPT = """
        你是专业的家庭财务助手。
        目标：帮助用户理解收支结构、预算健康度，并给出可执行建议。
        回答时优先使用用户真实数据；若数据不足，明确写“暂时缺少数据”并说明下一步。
        """ + OUTPUT_STYLE;

    private static final String STOCK_SYSTEM_PROMPT = """
        你是专业的股票信息助手。
        目标：基于可得行情信息给出稳健分析，避免绝对化表述。
        每次回答都必须包含风险提示：“投资有风险，决策需谨慎”。
        """ + OUTPUT_STYLE;

    private static final Pattern STOCK_SNAPSHOT_PATTERN = Pattern.compile(
        "^([^:]+):\\s*current=([\\d.\\-]+)\\s+open=([\\d.\\-]+)\\s+close=([\\d.\\-]+)\\s+high=([\\d.\\-]+)\\s+low=([\\d.\\-]+)\\s+volume=([\\d.\\-]+).*$"
    );

    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final CategoryRepository categoryRepository;
    private final WebClient.Builder webClientBuilder;
    private final AiGateway aiGateway;
    private final FinanceIntentClassifier financeIntentClassifier;

    /**
     * Main chat entry.
     *
     * <p>Flow: assistant type normalization -> context/prompt build -> model call -> reply normalization.
     */
    public AIChatResponse chat(Long userId, String message, String assistantType) {
        String safeMessage = message == null ? "" : message.trim();
        if (safeMessage.isBlank()) {
            return new AIChatResponse("请先输入你想咨询的问题。");
        }

        String type = normalizeType(assistantType);
        String prompt;
        String systemPrompt;
        Map<String, Object> financeContext = null;
        String stockData = null;

        if ("stock".equals(type)) {
            stockData = fetchStockData(safeMessage);
            prompt = buildStockPrompt(safeMessage, stockData);
            systemPrompt = STOCK_SYSTEM_PROMPT;
        } else {
            financeContext = buildFinanceContext(userId);
            prompt = buildFinancePrompt(safeMessage, financeContext);
            systemPrompt = FINANCE_SYSTEM_PROMPT;
        }

        String reply = aiGateway.chat(systemPrompt, prompt, null, type);
        return new AIChatResponse(normalizeReply(type, safeMessage, reply, financeContext, stockData));
    }

    private String normalizeType(String assistantType) {
        return "stock".equalsIgnoreCase(assistantType) ? "stock" : "finance";
    }

    /**
     * Fetches stock snapshot text used as stock-answer context.
     */
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
            } catch (Exception ex) {
                log.error("Fetch stock data failed for code={}", code, ex);
            }
        }

        return stockData.toString();
    }

    /**
     * Extracts six-digit stock codes from user text and maps to exchange-prefixed symbols.
     */
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

    /**
     * Queries Sina quote endpoint and returns a compact snapshot line.
     */
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

        return String.format(
            "%s: current=%s open=%s close=%s high=%s low=%s volume=%s",
            parts[0], parts[3], parts[1], parts[2], parts[4], parts[5], parts[8]
        );
    }

    /**
     * Builds finance context including month summary, category expense, recent transactions and active budgets.
     */
    private Map<String, Object> buildFinanceContext(Long userId) {
        Map<String, Object> context = new HashMap<>();
        YearMonth currentMonth = YearMonth.now();
        LocalDate startDate = currentMonth.atDay(1);
        LocalDate endDate = currentMonth.atEndOfMonth();

        BigDecimal totalIncome = transactionRepository.sumByUserIdAndTypeAndDateBetween(userId, 1, startDate, endDate);
        BigDecimal totalExpense = transactionRepository.sumByUserIdAndTypeAndDateBetween(userId, 2, startDate, endDate);

        context.put("period", startDate + " to " + endDate);
        context.put("totalIncome", totalIncome != null ? totalIncome : BigDecimal.ZERO);
        context.put("totalExpense", totalExpense != null ? totalExpense : BigDecimal.ZERO);
        context.put(
            "recentTransactions",
            transactionRepository.findByUserIdAndDateBetween(userId, startDate, endDate)
                .stream()
                .limit(10)
                .map(this::formatTransaction)
                .toList()
        );
        context.put(
            "categoryExpenses",
            formatCategoryExpenses(transactionRepository.sumByCategoryAndType(userId, 2, startDate, endDate))
        );
        context.put(
            "activeBudgets",
            budgetRepository.findActiveBudgets(userId, LocalDate.now())
                .stream()
                .map(this::formatBudget)
                .toList()
        );
        return context;
    }

    private String formatTransaction(Transaction transaction) {
        String categoryName = categoryRepository.findById(transaction.getCategoryId())
            .map(Category::getName)
            .orElse("未分类");

        return String.format(
            "%s | %s | %s | %.2f | %s",
            transaction.getDate(),
            transaction.getType() == 1 ? "收入" : "支出",
            categoryName,
            transaction.getAmount(),
            transaction.getRemark() != null ? transaction.getRemark() : ""
        );
    }

    private Map<String, Object> formatBudget(Budget budget) {
        Map<String, Object> item = new HashMap<>();
        item.put("startDate", budget.getStartDate());
        item.put("endDate", budget.getEndDate());
        item.put("amount", budget.getAmount());
        item.put("categoryId", budget.getCategoryId());

        if (budget.getCategoryId() != null) {
            categoryRepository.findById(budget.getCategoryId()).ifPresent(category -> item.put("categoryName", category.getName()));
        } else {
            item.put("categoryName", "总预算");
        }
        return item;
    }

    private List<Map<String, Object>> formatCategoryExpenses(List<Object[]> categoryExpenses) {
        return categoryExpenses.stream()
            .map(row -> {
                Long categoryId = (Long) row[0];
                BigDecimal amount = (BigDecimal) row[1];

                Map<String, Object> item = new HashMap<>();
                item.put("amount", amount);
                item.put(
                    "categoryName",
                    categoryRepository.findById(categoryId).map(Category::getName).orElse("未分类")
                );
                return item;
            })
            .toList();
    }

    @SuppressWarnings("unchecked")
    private String buildFinancePrompt(String message, Map<String, Object> context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户问题：").append(message).append("\n\n");
        prompt.append("=== 财务上下文数据 ===\n");
        prompt.append("统计周期：").append(context.get("period")).append("\n");
        prompt.append("本期收入：").append(formatMoney(toDecimal(context.get("totalIncome")))).append("\n");
        prompt.append("本期支出：").append(formatMoney(toDecimal(context.get("totalExpense")))).append("\n");

        List<Map<String, Object>> categoryExpenses = (List<Map<String, Object>>) context.get("categoryExpenses");
        if (categoryExpenses != null && !categoryExpenses.isEmpty()) {
            prompt.append("分类支出：\n");
            for (Map<String, Object> category : categoryExpenses) {
                prompt.append("- ")
                    .append(category.get("categoryName"))
                    .append("：")
                    .append(formatMoney(toDecimal(category.get("amount"))))
                    .append("\n");
            }
        }

        List<Map<String, Object>> activeBudgets = (List<Map<String, Object>>) context.get("activeBudgets");
        if (activeBudgets != null && !activeBudgets.isEmpty()) {
            prompt.append("进行中的预算：\n");
            for (Map<String, Object> budget : activeBudgets) {
                prompt.append(String.format(
                    "- %s：%s（%s ~ %s）%n",
                    budget.get("categoryName"),
                    formatMoney(toDecimal(budget.get("amount"))),
                    budget.get("startDate"),
                    budget.get("endDate")
                ));
            }
        }

        List<String> recentTransactions = (List<String>) context.get("recentTransactions");
        if (recentTransactions != null && !recentTransactions.isEmpty()) {
            prompt.append("最近交易：\n");
            for (String transaction : recentTransactions) {
                prompt.append("- ").append(transaction).append("\n");
            }
        }

        prompt.append("\n请基于以上数据回答，禁止编造。");
        return prompt.toString();
    }

    private String buildStockPrompt(String message, String stockData) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户问题：").append(message).append("\n\n");
        if (stockData != null && !stockData.isBlank()) {
            prompt.append("=== 市场数据 ===\n").append(stockData);
        } else {
            prompt.append("暂时没有实时行情数据。\n");
        }
        prompt.append("\n请基于数据给出简明分析，并明确风险提示。");
        return prompt.toString();
    }

    @SuppressWarnings("unchecked")
    private String normalizeReply(
        String assistantType,
        String question,
        String reply,
        Map<String, Object> financeContext,
        String stockData
    ) {
        String normalized = reply == null ? "" : reply.trim();
        if (normalized.isBlank() || isGatewayErrorLike(normalized)) {
            return "stock".equals(assistantType)
                ? buildStockFallbackAnswer(question, stockData)
                : buildFinanceFallbackAnswer(question, financeContext);
        }

        if ("finance".equals(assistantType) && shouldUseFinanceTemplate(normalized)) {
            return buildFinanceFallbackAnswer(question, financeContext);
        }
        return normalized;
    }

    private boolean isGatewayErrorLike(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.startsWith("ai service returned invalid response")
            || lower.startsWith("ai service returned empty")
            || lower.startsWith("ai service error:")
            || "sorry, ai service is temporarily unavailable. please try again later.".equals(lower)
            || "抱歉，ai 服务暂时不可用，请稍后再试。".equals(lower)
            || "抱歉，AI 服务暂时不可用，请稍后再试。".equals(text);
    }

    private boolean shouldUseFinanceTemplate(String text) {
        boolean hasChinese = text.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF);
        if (!hasChinese && text.length() < 80) {
            return true;
        }
        return text.toLowerCase(Locale.ROOT).contains("invalid response format");
    }

    @SuppressWarnings("unchecked")
    private String buildFinanceFallbackAnswer(String question, Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return """
                先给你一个基于当前状态的稳健建议。
                - 关键数据：暂时无法获取完整统计
                - 建议：稍后重试，或先查看“统计报表”页面确认收支趋势
                """;
        }

        BigDecimal totalIncome = toDecimal(context.get("totalIncome"));
        BigDecimal totalExpense = toDecimal(context.get("totalExpense"));
        BigDecimal balance = totalIncome.subtract(totalExpense);
        String period = String.valueOf(context.getOrDefault("period", "--"));
        FinanceIntentClassifier.FinanceIntent intent = financeIntentClassifier.classify(question);

        List<Map<String, Object>> categoryExpenses = context.get("categoryExpenses") instanceof List<?> categoryList
            ? (List<Map<String, Object>>) categoryList
            : List.of();
        List<Map<String, Object>> activeBudgets = context.get("activeBudgets") instanceof List<?> budgetList
            ? (List<Map<String, Object>>) budgetList
            : List.of();
        List<String> recentTransactions = context.get("recentTransactions") instanceof List<?> txList
            ? (List<String>) txList
            : List.of();

        Map<String, Object> topExpenseCategory = categoryExpenses.stream()
            .max(Comparator.comparing(item -> toDecimal(item.get("amount"))))
            .orElse(null);
        String topCategoryName = topExpenseCategory == null
            ? "暂无"
            : String.valueOf(topExpenseCategory.getOrDefault("categoryName", "未分类"));
        BigDecimal topCategoryAmount = topExpenseCategory == null ? BigDecimal.ZERO : toDecimal(topExpenseCategory.get("amount"));
        int budgetCount = activeBudgets.size();

        return switch (intent.type()) {
            case BUDGET -> buildFinanceBudgetAnswer(
                question, period, totalIncome, totalExpense, balance, activeBudgets, topCategoryName, topCategoryAmount
            );
            case CATEGORY -> buildFinanceCategoryAnswer(question, period, totalExpense, categoryExpenses);
            case TRANSACTION -> buildFinanceTransactionAnswer(question, recentTransactions, intent.transactionType());
            case CASHFLOW -> buildFinanceCashflowAnswer(question, period, totalIncome, totalExpense, balance, topCategoryName);
            case UNKNOWN -> buildFinanceGeneralAnswer(
                question, period, totalIncome, totalExpense, balance, topCategoryName, topCategoryAmount, budgetCount
            );
        };
    }

    private String buildFinanceBudgetAnswer(
        String question,
        String period,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal balance,
        List<Map<String, Object>> activeBudgets,
        String topCategoryName,
        BigDecimal topCategoryAmount
    ) {
        String budgetPreview;
        if (activeBudgets.isEmpty()) {
            budgetPreview = "当前暂无生效预算，建议先建立“总预算 + 高频分类预算”。";
        } else {
            Map<String, Object> first = activeBudgets.get(0);
            String categoryName = String.valueOf(first.getOrDefault("categoryName", "总预算"));
            String amount = formatMoney(toDecimal(first.get("amount")));
            String start = String.valueOf(first.getOrDefault("startDate", "--"));
            String end = String.valueOf(first.getOrDefault("endDate", "--"));
            budgetPreview = categoryName + " 预算 " + amount + "（" + start + " ~ " + end + "）";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("基于预算意图识别，先给你预算执行结论。\n");
        builder.append("- 问题：").append(question).append("\n");
        builder.append("- 统计周期：").append(period).append("\n");
        builder.append("- 本期收入/支出/结余：")
            .append(formatMoney(totalIncome)).append(" / ")
            .append(formatMoney(totalExpense)).append(" / ")
            .append(formatMoney(balance)).append("\n");
        builder.append("- 预算快照：").append(budgetPreview).append("\n");
        builder.append("- 风险点：最大支出分类为 ")
            .append(topCategoryName)
            .append("（")
            .append(formatMoney(topCategoryAmount))
            .append("）\n");
        builder.append("- 建议：优先控制 ").append(topCategoryName).append(" 这类可变支出，并按周复盘预算偏差。");
        return builder.toString();
    }

    private String buildFinanceCategoryAnswer(
        String question,
        String period,
        BigDecimal totalExpense,
        List<Map<String, Object>> categoryExpenses
    ) {
        List<Map<String, Object>> topCategories = categoryExpenses.stream()
            .sorted((left, right) -> toDecimal(right.get("amount")).compareTo(toDecimal(left.get("amount"))))
            .limit(3)
            .toList();

        String topSummary = topCategories.isEmpty()
            ? "暂无分类支出数据"
            : topCategories.stream()
                .map(item -> {
                    String name = String.valueOf(item.getOrDefault("categoryName", "未分类"));
                    BigDecimal amount = toDecimal(item.get("amount"));
                    String ratio = totalExpense.compareTo(BigDecimal.ZERO) > 0
                        ? amount.multiply(BigDecimal.valueOf(100)).divide(totalExpense, 2, RoundingMode.HALF_UP) + "%"
                        : "0.00%";
                    return name + " " + formatMoney(amount) + "（" + ratio + "）";
                })
                .reduce((a, b) -> a + "；" + b)
                .orElse("暂无分类支出数据");

        StringBuilder builder = new StringBuilder();
        builder.append("基于分类意图识别，先给你支出结构结论。\n");
        builder.append("- 问题：").append(question).append("\n");
        builder.append("- 统计周期：").append(period).append("\n");
        builder.append("- 本期总支出：").append(formatMoney(totalExpense)).append("\n");
        builder.append("- TOP3 分类：").append(topSummary).append("\n");
        builder.append("- 建议：优先给前 1~2 个高占比分类设置硬上限，防止月末挤压结余。");
        return builder.toString();
    }

    private String buildFinanceTransactionAnswer(String question, List<String> recentTransactions, Integer transactionType) {
        String expectedType = transactionType == null ? "" : (transactionType == 1 ? "收入" : "支出");
        List<String> filtered = recentTransactions.stream()
            .filter(item -> expectedType.isBlank() || item.contains("| " + expectedType + " |"))
            .limit(5)
            .toList();

        String recentSummary = filtered.isEmpty() ? "暂无符合条件的流水" : String.join("；", filtered);
        List<BigDecimal> amounts = filtered.stream().map(this::extractAmountFromTransactionLine).toList();
        BigDecimal maxAmount = amounts.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        StringBuilder builder = new StringBuilder();
        builder.append("基于流水意图识别，先给你最近交易摘要。\n");
        builder.append("- 问题：").append(question).append("\n");
        if (!expectedType.isBlank()) {
            builder.append("- 流水类型：").append(expectedType).append("\n");
        }
        builder.append("- 最近记录：").append(recentSummary).append("\n");
        if (maxAmount.compareTo(BigDecimal.ZERO) > 0) {
            builder.append("- 观察重点：最近记录中最大金额约 ").append(formatMoney(maxAmount)).append("\n");
        }
        builder.append("- 建议：先标记“高金额 + 高频出现”的分类，再决定是否下调该类预算。");
        return builder.toString();
    }

    private String buildFinanceCashflowAnswer(
        String question,
        String period,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal balance,
        String topCategoryName
    ) {
        BigDecimal ratio = totalIncome.compareTo(BigDecimal.ZERO) > 0
            ? totalExpense.multiply(BigDecimal.valueOf(100)).divide(totalIncome, 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        StringBuilder builder = new StringBuilder();
        builder.append("基于收支意图识别，先给你现金流结论。\n");
        builder.append("- 问题：").append(question).append("\n");
        builder.append("- 统计周期：").append(period).append("\n");
        builder.append("- 收入/支出/结余：")
            .append(formatMoney(totalIncome)).append(" / ")
            .append(formatMoney(totalExpense)).append(" / ")
            .append(formatMoney(balance)).append("\n");
        builder.append("- 支出收入比：").append(ratio).append("%\n");
        builder.append("- 建议：保持收入稳定，同时优先优化 ").append(topCategoryName).append(" 这类可变支出。");
        return builder.toString();
    }

    private String buildFinanceGeneralAnswer(
        String question,
        String period,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal balance,
        String topCategoryName,
        BigDecimal topCategoryAmount,
        int budgetCount
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("基于账本数据，先给你一个稳健结论。\n");
        builder.append("- 问题：").append(question).append("\n");
        builder.append("- 统计周期：").append(period).append("\n");
        builder.append("- 本期收入：").append(formatMoney(totalIncome)).append("\n");
        builder.append("- 本期支出：").append(formatMoney(totalExpense)).append("\n");
        builder.append("- 本期结余：").append(formatMoney(balance)).append("\n");
        builder.append("- 最大支出分类：")
            .append(topCategoryName)
            .append("（")
            .append(formatMoney(topCategoryAmount))
            .append("）\n");
        builder.append("- 进行中预算数：").append(budgetCount).append("\n");
        builder.append("- 建议：本周优先控制 ").append(topCategoryName).append(" 与可变消费，保持每周复盘。");
        return builder.toString();
    }

    private String buildStockFallbackAnswer(String question, String stockData) {
        StockTheme theme = detectStockTheme(question);
        StockThemeProfile profile = stockThemeProfile(theme);
        List<String> snapshots = buildStockSnapshotCards(stockData);

        StringBuilder builder = new StringBuilder();
        builder.append("基于当前行情快照，给你一个").append(profile.sceneLabel()).append("稳健观察框架。\n");
        builder.append("- 问题：").append(question).append("\n");
        builder.append("- 观察主线：").append(profile.mainline()).append("\n");
        if (!snapshots.isEmpty()) {
            builder.append("- 行情快照：").append(String.join("；", snapshots)).append("\n");
        } else {
            builder.append("- 行情快照：暂无实时行情数据\n");
        }
        builder.append("- 重点观察：").append(profile.focus()).append("\n");
        builder.append("- 建议：").append(profile.action()).append("\n");
        builder.append("- 风险提示：投资有风险，决策需谨慎。");
        return builder.toString();
    }

    private List<String> buildStockSnapshotCards(String stockData) {
        if (stockData == null || stockData.isBlank()) {
            return List.of();
        }
        List<String> cards = new ArrayList<>();
        String[] lines = stockData.split("\\r?\\n");
        for (String line : lines) {
            String formatted = formatStockSnapshotLine(line);
            if (formatted != null && !formatted.isBlank()) {
                cards.add(formatted);
            }
            if (cards.size() >= 2) {
                break;
            }
        }
        return cards;
    }

    private String formatStockSnapshotLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        Matcher matcher = STOCK_SNAPSHOT_PATTERN.matcher(line.trim());
        if (!matcher.matches()) {
            return line.trim();
        }

        String name = matcher.group(1);
        BigDecimal current = toDecimal(matcher.group(2));
        BigDecimal open = toDecimal(matcher.group(3));
        BigDecimal close = toDecimal(matcher.group(4));
        BigDecimal high = toDecimal(matcher.group(5));
        BigDecimal low = toDecimal(matcher.group(6));

        BigDecimal changePct = BigDecimal.ZERO;
        if (close.compareTo(BigDecimal.ZERO) > 0) {
            changePct = current.subtract(close)
                .multiply(BigDecimal.valueOf(100))
                .divide(close, 2, RoundingMode.HALF_UP);
        }

        BigDecimal rangePct = BigDecimal.ZERO;
        if (open.compareTo(BigDecimal.ZERO) > 0) {
            rangePct = high.subtract(low)
                .multiply(BigDecimal.valueOf(100))
                .divide(open, 2, RoundingMode.HALF_UP);
        }

        String changePrefix = changePct.compareTo(BigDecimal.ZERO) > 0 ? "+" : "";
        return String.format(
            "%s %.2f（涨跌%s%.2f%%，振幅%.2f%%）",
            name,
            current.doubleValue(),
            changePrefix,
            changePct.doubleValue(),
            rangePct.doubleValue()
        );
    }

    private StockTheme detectStockTheme(String question) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (containsAny(text, "消费", "白酒", "食品饮料", "家电", "零售", "商贸", "旅游", "酒店")) {
            return StockTheme.CONSUMPTION;
        }
        if (containsAny(text, "科技", "半导体", "芯片", "ai", "人工智能", "软件", "互联网", "通信", "算力")) {
            return StockTheme.TECH;
        }
        if (containsAny(text, "医药", "医疗", "创新药", "生物", "器械", "医美")) {
            return StockTheme.HEALTHCARE;
        }
        if (containsAny(text, "金融", "银行", "保险", "券商", "信托")) {
            return StockTheme.FINANCE;
        }
        if (containsAny(text, "新能源", "光伏", "储能", "锂电", "电池", "风电", "电动车")) {
            return StockTheme.NEW_ENERGY;
        }
        if (containsAny(text, "大盘", "指数", "上证", "深证", "创业板", "沪深300")) {
            return StockTheme.INDEX;
        }
        return StockTheme.GENERAL;
    }

    private StockThemeProfile stockThemeProfile(StockTheme theme) {
        return switch (theme) {
            case CONSUMPTION -> new StockThemeProfile(
                "消费板块的",
                "先看需求恢复与盈利兑现，再看估值匹配度",
                "社零与客流、龙头毛利率、库存周转",
                "优先跟踪业绩确定性较高的细分龙头，分批决策，避免追涨"
            );
            case TECH -> new StockThemeProfile(
                "科技板块的",
                "先看产业景气和订单兑现，再看估值消化能力",
                "订单增速、研发投入、产品迭代与算力需求",
                "把仓位拆分为试探仓和确认仓，等待放量突破后再加仓"
            );
            case HEALTHCARE -> new StockThemeProfile(
                "医药板块的",
                "先看政策与产品周期，再看现金流质量",
                "临床/集采进展、费用率变化、回款周期",
                "优先看现金流稳健且产品线清晰的标的，避免单一事件驱动"
            );
            case FINANCE -> new StockThemeProfile(
                "金融板块的",
                "先看利差与资产质量，再看估值修复节奏",
                "净息差、不良率、资本充足率与成交活跃度",
                "以分红稳定和风控稳健为优先，避免单日情绪化追高"
            );
            case NEW_ENERGY -> new StockThemeProfile(
                "新能源板块的",
                "先看产能利用率与价格趋势，再看需求兑现",
                "电池/组件价格、装机增速、海外订单与补贴政策",
                "把握景气拐点，分段建仓并设置回撤阈值"
            );
            case INDEX -> new StockThemeProfile(
                "指数观察的",
                "先看量价结构与板块轮动，再看风险偏好",
                "成交额、涨跌家数、权重股带动与北向资金",
                "在量能放大前保持节奏，优先仓位管理和止损纪律"
            );
            case GENERAL -> new StockThemeProfile(
                "市场观察的",
                "先看量价结构，再看行业主线是否清晰",
                "成交量、波动区间、资金流向与板块强弱",
                "先观察后行动，分批决策，不要追涨杀跌"
            );
        };
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal extractAmountFromTransactionLine(String line) {
        if (line == null || line.isBlank()) {
            return BigDecimal.ZERO;
        }
        String[] parts = line.split("\\|");
        if (parts.length < 4) {
            return BigDecimal.ZERO;
        }
        return toDecimal(parts[3].trim());
    }

    private enum StockTheme {
        CONSUMPTION,
        TECH,
        HEALTHCARE,
        FINANCE,
        NEW_ENERGY,
        INDEX,
        GENERAL
    }

    private record StockThemeProfile(String sceneLabel, String mainline, String focus, String action) {
    }

    private BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
        } catch (Exception ex) {
            return BigDecimal.ZERO;
        }
    }

    private String formatMoney(BigDecimal value) {
        return "¥" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
