package com.mamoji.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.ai.AiGateway;
import com.mamoji.ai.AiModelRouter;
import com.mamoji.ai.memory.ConversationMemoryService;
import com.mamoji.ai.memory.ConversationTurn;
import com.mamoji.ai.metrics.AiMetricsService;
import com.mamoji.ai.model.StructuredAiResponse;
import com.mamoji.ai.prompt.PromptVariantService;
import com.mamoji.ai.quality.AiQualityGateService;
import com.mamoji.ai.rag.KnowledgeRetriever;
import com.mamoji.ai.rag.KnowledgeSnippet;
import com.mamoji.ai.tool.AiToolResult;
import com.mamoji.ai.tool.AiToolRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReActAgentService {

    private final AiGateway aiGateway;
    private final AiToolRouter aiToolRouter;
    private final ConversationMemoryService memoryService;
    private final KnowledgeRetriever knowledgeRetriever;
    private final PromptVariantService promptVariantService;
    private final AiQualityGateService qualityGateService;
    private final AiMetricsService aiMetricsService;
    private final AiModelRouter aiModelRouter;
    private final StructuredAnswerParser structuredAnswerParser;
    private final ObjectMapper objectMapper;

    public String processMessage(Long userId, String message, String assistantType, String sessionId) {
        return processMessageStructured(userId, message, assistantType, sessionId).answer();
    }

    public StructuredAiResponse processMessageStructured(Long userId, String message, String assistantType, String sessionId) {
        try {
            String traceId = UUID.randomUUID().toString().substring(0, 8);
            String type = normalizeAssistantType(assistantType);
            String sessionKey = buildSessionKey(userId, type, sessionId);
            ToolPlan plan = chooseToolPlan(type, message);

            List<String> actions = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            List<String> sources = new ArrayList<>();

            List<KnowledgeSnippet> snippets = knowledgeRetriever.retrieve(type, message, 2);
            snippets.forEach(snippet -> sources.add(snippet.source() + ":" + snippet.title()));

            String toolPayload = "";
            if (plan != null) {
                long toolStart = System.currentTimeMillis();
                AiToolResult toolResult = aiToolRouter.route(userId, plan.domain, plan.params);
                long toolElapsed = System.currentTimeMillis() - toolStart;
                aiMetricsService.recordToolCall(toolResult.toolName(), toolResult.success(), toolElapsed);
                actions.add(toolResult.toolName());
                if (!toolResult.success()) {
                    warnings.add("Tool call failed: " + toolResult.error());
                    return buildErrorResponse("抱歉，工具调用失败，请稍后重试。", warnings, actions, sources, message);
                }
                toolPayload = toolResult.payload();
                sources.add("tool:" + toolResult.toolName());
            }

            String prompt = buildPromptWithContext(message, toolPayload, snippets, memoryService.recent(sessionKey, 8));
            PromptVariantService.PromptVariant promptVariant = promptVariantService.pick(type, sessionKey);
            AiModelRouter.RoutingDecision routingDecision = aiModelRouter.pickPrimaryModelDecision(type, message);
            String routedModel = routingDecision.model();
            aiMetricsService.recordModelRouteReason(type, routedModel, routingDecision.reason());
            String rawAnswer = aiGateway.chat(promptVariant.systemPrompt(), prompt, routedModel, type);

            StructuredAnswerParser.ParsedAnswer parsed = parseOrRepairStructuredAnswer(
                promptVariant.systemPrompt(),
                prompt,
                rawAnswer,
                traceId,
                type,
                message,
                toolPayload
            );

            String answer = parsed.answer();
            warnings.addAll(parsed.warnings());
            sources.addAll(parsed.sources());
            actions.addAll(parsed.actions());

            memoryService.append(sessionKey, "user", message);
            memoryService.append(sessionKey, "assistant", answer);

            if (warnings.stream().noneMatch(this::isSchemaWarning)) {
                warnings.addAll(qualityGateService.validate(type, message, answer));
            }
            aiMetricsService.recordQualityWarnings(type, warnings.size());

            Map<String, Object> usage = Map.of(
                "inputChars", prompt.length(),
                "outputChars", answer.length(),
                "estimatedTokens", estimateTokens(prompt, answer),
                "promptVariant", promptVariant.variant(),
                "promptExperimentId", promptVariant.experimentId(),
                "promptBucket", promptVariant.bucket()
            );

            return new StructuredAiResponse(answer, sources, actions, warnings, usage);
        } catch (Exception ex) {
            log.error("ReAct processing failed: {}", ex.getMessage(), ex);
            return buildErrorResponse("抱歉，处理请求时发生错误，请稍后重试。", List.of("internal_error"), List.of(), List.of(), message);
        }
    }

    private StructuredAiResponse buildErrorResponse(
        String answer,
        List<String> warnings,
        List<String> actions,
        List<String> sources,
        String question
    ) {
        return new StructuredAiResponse(
            answer,
            sources,
            actions,
            warnings,
            Map.of("inputChars", question != null ? question.length() : 0, "outputChars", answer.length(), "estimatedTokens", estimateTokens(question, answer))
        );
    }

    private String buildPromptWithContext(
        String question,
        String toolPayload,
        List<KnowledgeSnippet> snippets,
        List<ConversationTurn> turns
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("User question: ").append(question).append("\n\n");

        if (toolPayload != null && !toolPayload.isBlank()) {
            prompt.append("Tool result(JSON):\n").append(toolPayload).append("\n\n");
        }

        if (!snippets.isEmpty()) {
            prompt.append("Knowledge snippets:\n");
            for (KnowledgeSnippet snippet : snippets) {
                prompt.append("- ").append(snippet.title()).append(": ").append(snippet.content()).append("\n");
            }
            prompt.append("\n");
        }

        if (!turns.isEmpty()) {
            prompt.append("Recent conversation:\n");
            for (ConversationTurn turn : turns) {
                prompt.append("- ").append(turn.role()).append(": ").append(turn.content()).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("Please answer in Chinese, concise and actionable.\n");
        prompt.append("Answer formatting requirements for `answer` field:\n");
        prompt.append("1) First line: 结论：<one short sentence>\n");
        prompt.append("2) Then include section `关键数据：` with 2-4 bullet lines like `- 指标：数值`\n");
        prompt.append("3) Then include section `建议：` with 1-3 bullet lines\n");
        prompt.append("4) Keep answer compact (max 8 lines), avoid long paragraphs\n");
        prompt.append("Return JSON only with schema: ")
            .append("{\"answer\":\"string\",\"warnings\":[\"string\"],\"sources\":[\"string\"],\"actions\":[\"string\"]}.");
        return prompt.toString();
    }

    private StructuredAnswerParser.ParsedAnswer parseOrRepairStructuredAnswer(
        String systemPrompt,
        String originalPrompt,
        String rawAnswer,
        String traceId,
        String assistantType,
        String question,
        String toolPayload
    ) {
        StructuredAnswerParser.ParsedAnswer parsed = structuredAnswerParser.parse(rawAnswer).orElse(null);
        if (parsed != null) {
            return parsed;
        }

        log.warn("Structured answer parse failed traceId={} stage=primary preview={}", traceId, preview(rawAnswer));
        String repairPrompt = buildRepairPrompt(originalPrompt, rawAnswer);
        String repairedRawAnswer = aiGateway.chat(strictJsonRepairSystemPrompt(), repairPrompt, null, assistantType);
        StructuredAnswerParser.ParsedAnswer repaired = structuredAnswerParser.parse(repairedRawAnswer).orElse(null);
        if (repaired != null) {
            List<String> repairedWarnings = new ArrayList<>(repaired.warnings());
            repairedWarnings.add("schema_repair_retry");
            log.info("Structured answer repaired traceId={} stage=repair-success", traceId);
            return new StructuredAnswerParser.ParsedAnswer(repaired.answer(), repairedWarnings, repaired.sources(), repaired.actions());
        }

        log.warn("Structured answer parse failed traceId={} stage=repair preview={}", traceId, preview(repairedRawAnswer));
        String fallbackAnswer = buildSchemaFallbackAnswer(rawAnswer, assistantType, question, toolPayload);
        return new StructuredAnswerParser.ParsedAnswer(
            fallbackAnswer,
            List.of("schema_parse_failed"),
            List.of(),
            List.of()
        );
    }

    private String buildRepairPrompt(String originalPrompt, String rawAnswer) {
        return "Reformat the following model answer into strict JSON only. "
            + "Schema: {\"answer\":\"string\",\"warnings\":[\"string\"],\"sources\":[\"string\"],\"actions\":[\"string\"]}. "
            + "Do not include markdown code fences.\n\n"
            + "Original prompt:\n"
            + originalPrompt
            + "\n\nRaw answer:\n"
            + rawAnswer;
    }

    private String strictJsonRepairSystemPrompt() {
        return "You are a strict JSON formatter. Return JSON only. "
            + "Do not include markdown, explanations or additional keys.";
    }

    private String buildSchemaFallbackAnswer(String rawAnswer, String assistantType, String question, String toolPayload) {
        if (isGatewayErrorLike(rawAnswer)) {
            String toolSummary = buildToolSummaryFallback(assistantType, toolPayload);
            if (toolSummary != null) {
                return toolSummary;
            }
            String questionFallback = buildQuestionBasedFallback(assistantType, question);
            if (questionFallback != null) {
                return questionFallback;
            }
            return "抱歉，AI 服务暂时不稳定，我暂时无法生成完整分析。请稍后重试。";
        }
        return sanitizeRawAnswer(rawAnswer);
    }

    private String buildQuestionBasedFallback(String assistantType, String question) {
        String text = question == null ? "" : question.toLowerCase();
        if ("finance".equals(assistantType)) {
            if (containsAny(text, "节流", "省钱", "预算", "建议", "控制支出")) {
                return "结论：本月可先从可变支出入手节流。\n"
                    + "关键数据：\n"
                    + "- 优先关注饮食、娱乐、冲动消费三类\n"
                    + "- 建议每周至少复盘 1 次支出\n"
                    + "建议：\n"
                    + "- 先设本周非必要支出上限\n"
                    + "- 连续 7 天记录每笔可变支出\n"
                    + "- 优先取消低使用率订阅";
            }
            return "抱歉，AI 服务暂时不稳定。建议先查看“预算执行率、前三支出分类、最近7天交易”再继续分析。";
        }
        if ("stock".equals(assistantType)) {
            return "抱歉，AI 服务暂时不稳定。建议先关注指数走势、成交量变化和板块轮动，投资有风险请谨慎决策。";
        }
        return null;
    }

    private String buildToolSummaryFallback(String assistantType, String toolPayload) {
        if (toolPayload == null || toolPayload.isBlank()) {
            return null;
        }
        if ("finance".equals(assistantType)) {
            return buildFinanceToolSummary(toolPayload);
        }
        if ("stock".equals(assistantType)) {
            return "已获取市场工具数据，但模型响应格式异常。建议稍后重试。";
        }
        return null;
    }

    private String buildFinanceToolSummary(String toolPayload) {
        try {
            JsonNode node = objectMapper.readTree(toolPayload);
            if (node.has("budgetAmount") && node.has("spent")) {
                return budgetSummary(node);
            }
            if (node.has("totalIncome") && node.has("totalExpense")) {
                return incomeExpenseSummary(node);
            }
            if (node.has("categories") && node.path("categories").isArray()) {
                return categorySummary(node);
            }
            if (node.has("count") && node.has("transactions")) {
                return transactionSummary(node);
            }
        } catch (Exception ex) {
            log.warn("Build finance fallback summary failed: {}", ex.getMessage());
        }
        return "已获取财务工具数据，但模型响应格式异常。请稍后重试。";
    }

    private String budgetSummary(JsonNode node) {
        String name = text(node, "name", "当前预算");
        BigDecimal budgetAmount = decimal(node, "budgetAmount");
        BigDecimal spent = decimal(node, "spent");
        BigDecimal remaining = decimal(node, "remaining");
        double usageRate = node.path("usageRate").asDouble(0D);
        String status = text(node, "status", "normal");
        String period = text(node, "period", "");

        StringBuilder builder = new StringBuilder();
        builder.append("我已经查到你的预算执行情况：\n");
        builder.append("- 预算名称：").append(name).append("\n");
        builder.append("- 预算总额：").append(formatAmount(budgetAmount))
            .append("，已支出：").append(formatAmount(spent))
            .append("，剩余：").append(formatAmount(remaining)).append("\n");
        builder.append("- 使用率：").append(formatPercent(usageRate)).append("\n");
        builder.append("- 状态：").append(describeBudgetStatus(status));
        if (!period.isBlank()) {
            builder.append("\n- 统计区间：").append(period);
        }
        return builder.toString();
    }

    private String incomeExpenseSummary(JsonNode node) {
        BigDecimal totalIncome = decimal(node, "totalIncome");
        BigDecimal totalExpense = decimal(node, "totalExpense");
        BigDecimal balance = decimal(node, "balance");
        String period = text(node, "period", "");

        StringBuilder builder = new StringBuilder();
        builder.append("我已经查到当前收支数据：\n");
        builder.append("- 收入：").append(formatAmount(totalIncome))
            .append("，支出：").append(formatAmount(totalExpense))
            .append("，结余：").append(formatAmount(balance));
        if (!period.isBlank()) {
            builder.append("\n- 统计区间：").append(period);
        }
        BigDecimal ratioBase = totalIncome.compareTo(BigDecimal.ZERO) > 0 ? totalIncome : BigDecimal.ONE;
        BigDecimal expenseRatio = totalExpense.divide(ratioBase, 4, RoundingMode.HALF_UP);
        if (expenseRatio.compareTo(new BigDecimal("0.85")) >= 0) {
            builder.append("\n- 建议：当前支出偏高，先收紧餐饮/娱乐等可变开销。");
        } else {
            builder.append("\n- 建议：支出结构总体可控，继续保持周度复盘。");
        }
        return builder.toString();
    }

    private String categorySummary(JsonNode node) {
        JsonNode categories = node.path("categories");
        StringBuilder builder = new StringBuilder("我已经查到分类支出情况：\n");
        int limit = Math.min(3, categories.size());
        for (int i = 0; i < limit; i++) {
            JsonNode item = categories.get(i);
            builder.append("- ")
                .append(text(item, "categoryName", "未分类"))
                .append("：")
                .append(formatAmount(decimal(item, "amount")))
                .append("（")
                .append(formatPercent(item.path("percentage").asDouble(0D)))
                .append("）\n");
        }
        builder.append("建议优先检查占比最高的 1~2 个分类。");
        return builder.toString();
    }

    private String transactionSummary(JsonNode node) {
        int count = node.path("count").asInt(0);
        return "已查询到最近交易记录，共 " + count + " 条。模型响应格式异常，建议稍后重试获取完整分析。";
    }

    private String describeBudgetStatus(String status) {
        return switch (status) {
            case "over" -> "已超预算，建议立即收紧非必要开销";
            case "warning" -> "接近预算上限，建议本周控制可变支出";
            default -> "预算执行正常";
        };
    }

    private String formatAmount(BigDecimal value) {
        BigDecimal amount = value != null ? value : BigDecimal.ZERO;
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String formatPercent(double value) {
        BigDecimal rate = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
        return rate.toPlainString() + "%";
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return BigDecimal.ZERO;
        }
        try {
            if (value.isNumber()) {
                return value.decimalValue();
            }
            String text = value.asText();
            if (text == null || text.isBlank()) {
                return BigDecimal.ZERO;
            }
            return new BigDecimal(text.trim());
        } catch (Exception ex) {
            return BigDecimal.ZERO;
        }
    }

    private String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        String text = value.asText();
        return (text == null || text.isBlank()) ? defaultValue : text.trim();
    }

    private boolean isGatewayErrorLike(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return true;
        }
        String normalized = rawAnswer.trim();
        return normalized.startsWith("AI service returned invalid response")
            || normalized.startsWith("AI service returned empty")
            || normalized.startsWith("AI service error:")
            || normalized.equals("Sorry, AI service is temporarily unavailable. Please try again later.");
    }

    private String sanitizeRawAnswer(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return "AI returned empty response";
        }
        return rawAnswer;
    }

    private String preview(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return "empty";
        }
        String normalized = rawAnswer.replaceAll("\\s+", " ").trim();
        return normalized.length() > 160 ? normalized.substring(0, 160) + "..." : normalized;
    }

    private boolean isSchemaWarning(String warning) {
        return warning != null && warning.startsWith("schema_");
    }

    private ToolPlan chooseToolPlan(String assistantType, String message) {
        if ("stock".equals(assistantType)) {
            return chooseStockPlan(message);
        }
        return chooseFinancePlan(message);
    }

    private ToolPlan chooseFinancePlan(String message) {
        String lower = message == null ? "" : message.toLowerCase();
        if (containsAny(lower, "预算", "budget")) {
            return new ToolPlan("finance", mapOf("operation", "query_budget"));
        }
        if (containsAny(lower, "分类", "category", "占比")) {
            return new ToolPlan("finance", mapOf("operation", "query_category_stats", "type", 2));
        }
        if (containsAny(lower, "交易", "记录", "流水")) {
            return new ToolPlan("finance", mapOf("operation", "query_transactions"));
        }
        if (containsAny(lower, "支出", "收入", "收支", "开销", "节流", "省钱", "优化", "建议")) {
            return new ToolPlan("finance", mapOf("operation", "query_income_expense"));
        }
        return null;
    }

    private ToolPlan chooseStockPlan(String message) {
        String lower = message == null ? "" : message.toLowerCase();
        if (containsAny(lower, "大盘", "指数", "market")) {
            return new ToolPlan("stock", mapOf("operation", "query_market_index"));
        }
        if (containsAny(lower, "新闻", "news")) {
            return new ToolPlan("stock", mapOf("operation", "get_stock_news", "stockCode", extractStockCode(message)));
        }
        if (containsAny(lower, "搜索", "查找", "search")) {
            return new ToolPlan("stock", mapOf("operation", "search_stock", "keyword", message));
        }
        String code = extractStockCode(message);
        if (code != null) {
            return new ToolPlan("stock", mapOf("operation", "query_stock_quote", "stockCode", code));
        }
        return new ToolPlan("stock", mapOf("operation", "query_market_index"));
    }

    private String extractStockCode(String text) {
        if (text == null) {
            return null;
        }
        String digits = text.replaceAll(".*?(\\d{6}).*", "$1");
        return digits.matches("\\d{6}") ? digits : null;
    }

    private String normalizeAssistantType(String assistantType) {
        return (assistantType == null || assistantType.isBlank()) ? "finance" : assistantType.trim().toLowerCase();
    }

    private String buildSessionKey(Long userId, String assistantType, String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        return userId + ":" + assistantType + ":" + sid;
    }

    private int estimateTokens(String in, String out) {
        int chars = (in != null ? in.length() : 0) + (out != null ? out.length() : 0);
        return Math.max(1, chars / 4);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> mapOf(Object... kvs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kvs.length - 1; i += 2) {
            Object key = kvs[i];
            Object value = kvs[i + 1];
            if (key != null && value != null) {
                map.put(key.toString(), value);
            }
        }
        return map;
    }

    private record ToolPlan(String domain, Map<String, Object> params) {
    }
}
