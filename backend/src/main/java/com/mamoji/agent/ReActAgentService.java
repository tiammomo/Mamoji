package com.mamoji.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.ai.AiGateway;
import com.mamoji.ai.AiModelRouter;
import com.mamoji.ai.intent.FinanceIntentClassifier;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * ReAct 智能体编排服务。
 *
 * <p>负责工具路由、知识检索、结构化输出解析、质量门禁与记忆管理。
 */
public class ReActAgentService {

    private static final String EMPTY_QUESTION_ANSWER = "请先输入问题。";
    private static final String TOOL_FAILED_ANSWER = "工具调用失败，请稍后重试。";
    private static final String INTERNAL_ERROR_ANSWER = "系统处理异常，请稍后重试。";
    private static final String GATEWAY_UNAVAILABLE_ANSWER = "抱歉，AI 服务暂时不可用，请稍后再试。";

    private final AiGateway aiGateway;
    private final AiToolRouter aiToolRouter;
    private final ConversationMemoryService memoryService;
    private final KnowledgeRetriever knowledgeRetriever;
    private final PromptVariantService promptVariantService;
    private final AiQualityGateService qualityGateService;
    private final AiMetricsService aiMetricsService;
    private final AiModelRouter aiModelRouter;
    private final FinanceIntentClassifier financeIntentClassifier;
    private final StructuredAnswerParser structuredAnswerParser;
    private final ObjectMapper objectMapper;

    /**
     * 兼容纯文本输出的入口（仅返回 answer 字段）。
     */
    public String processMessage(Long userId, String message, String assistantType, String sessionId) {
        return processMessageStructured(userId, message, assistantType, sessionId).answer();
    }

    /**
     * 结构化输出主入口。
     *
     * <p>成功路径会附带 sources/actions/warnings/usage，异常路径保持同结构返回。
     */
    public StructuredAiResponse processMessageStructured(Long userId, String message, String assistantType, String sessionId) {
        String safeMessage = message == null ? "" : message.trim();
        if (safeMessage.isBlank()) {
            return buildErrorResponse(
                EMPTY_QUESTION_ANSWER,
                List.of("empty_question"),
                List.of(),
                List.of(),
                safeMessage
            );
        }

        try {
            String traceId = UUID.randomUUID().toString().substring(0, 8);
            String type = normalizeAssistantType(assistantType);
            String sessionKey = buildSessionKey(userId, type, sessionId);
            ToolPlan plan = chooseToolPlan(type, safeMessage);

            List<String> actions = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            List<String> sources = new ArrayList<>();

            List<KnowledgeSnippet> snippets = knowledgeRetriever.retrieve(type, safeMessage, 3);
            for (KnowledgeSnippet snippet : snippets) {
                sources.add(snippet.source() + ":" + snippet.title());
            }

            String toolPayload = "";
            if (plan != null) {
                long toolStart = System.currentTimeMillis();
                AiToolResult toolResult = aiToolRouter.route(userId, plan.domain, plan.params);
                long toolElapsed = System.currentTimeMillis() - toolStart;
                if (toolResult == null) {
                    warnings.add("tool_call_failed");
                    aiMetricsService.recordToolCall(plan.domain, false, toolElapsed);
                    return buildErrorResponse(TOOL_FAILED_ANSWER, warnings, actions, sources, safeMessage);
                }

                aiMetricsService.recordToolCall(toolResult.toolName(), toolResult.success(), toolElapsed);
                actions.add(toolResult.toolName());
                if (!toolResult.success()) {
                    warnings.add("tool_call_failed");
                    return buildErrorResponse(TOOL_FAILED_ANSWER, warnings, actions, sources, safeMessage);
                }
                toolPayload = toolResult.payload();
                sources.add("tool:" + toolResult.toolName());
            }

            List<ConversationTurn> recentTurns = memoryService.recent(sessionKey, 8);
            String prompt = buildPromptWithContext(type, safeMessage, toolPayload, snippets, recentTurns);
            PromptVariantService.PromptVariant promptVariant = promptVariantService.pick(type, sessionKey);
            AiModelRouter.RoutingDecision routingDecision = aiModelRouter.pickPrimaryModelDecision(type, safeMessage);
            String routedModel = routingDecision.model();
            aiMetricsService.recordModelRouteReason(type, routedModel, routingDecision.reason());

            String rawAnswer = aiGateway.chat(promptVariant.systemPrompt(), prompt, routedModel, type);
            StructuredAnswerParser.ParsedAnswer parsed = parseOrRepairStructuredAnswer(
                promptVariant.systemPrompt(),
                prompt,
                rawAnswer,
                traceId,
                type,
                safeMessage,
                toolPayload,
                routedModel
            );

            String answer = parsed.answer();
            warnings.addAll(parsed.warnings());
            sources.addAll(parsed.sources());
            actions.addAll(parsed.actions());

            List<String> qualityWarnings = List.of();
            if (warnings.stream().noneMatch(this::isSchemaWarning)) {
                qualityWarnings = safeList(qualityGateService.validate(type, safeMessage, answer));
                if (!qualityWarnings.isEmpty()) {
                    String rewritten = rewriteForQuality(
                        type,
                        safeMessage,
                        answer,
                        qualityWarnings,
                        toolPayload,
                        promptVariant.systemPrompt(),
                        routedModel
                    );
                    if (rewritten != null && !rewritten.isBlank()) {
                        answer = rewritten;
                        warnings.add("quality_rewrite_retry");
                        qualityWarnings = safeList(qualityGateService.validate(type, safeMessage, answer));
                    }
                }
            }
            warnings.addAll(qualityWarnings);
            warnings = deduplicate(warnings);
            sources = deduplicate(sources);
            actions = deduplicate(actions);

            memoryService.append(sessionKey, "user", safeMessage);
            memoryService.append(sessionKey, "assistant", answer);
            aiMetricsService.recordQualityWarnings(type, warnings.size());

            Map<String, Object> usage = new HashMap<>();
            usage.put("inputChars", prompt.length());
            usage.put("outputChars", answer.length());
            usage.put("estimatedTokens", estimateTokens(prompt, answer));
            usage.put("promptVariant", promptVariant.variant());
            usage.put("promptExperimentId", promptVariant.experimentId());
            usage.put("promptBucket", promptVariant.bucket());
            usage.put("qualityWarnings", qualityWarnings.size());

            return new StructuredAiResponse(answer, sources, actions, warnings, usage);
        } catch (Exception ex) {
            log.error("ReAct processing failed: {}", ex.getMessage(), ex);
            return buildErrorResponse(INTERNAL_ERROR_ANSWER, List.of("internal_error"), List.of(), List.of(), safeMessage);
        }
    }

    /**
     * 构建统一错误响应，确保失败路径字段完整且可追踪。
     */
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
            Map.of(
                "inputChars", question != null ? question.length() : 0,
                "outputChars", answer.length(),
                "estimatedTokens", estimateTokens(question, answer)
            )
        );
    }

    /**
     * 组装模型提示词：问题、工具结果、知识片段、近期对话和输出约束。
     */
    private String buildPromptWithContext(
        String assistantType,
        String question,
        String toolPayload,
        List<KnowledgeSnippet> snippets,
        List<ConversationTurn> turns
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("[Question]\n").append(question).append("\n\n");

        if (toolPayload != null && !toolPayload.isBlank()) {
            prompt.append("[Tool Result JSON]\n").append(toolPayload).append("\n\n");
        } else {
            prompt.append("[Tool Result JSON]\nnone\n\n");
        }

        if (!snippets.isEmpty()) {
            prompt.append("[Knowledge Snippets]\n");
            for (KnowledgeSnippet snippet : snippets) {
                prompt.append("- ")
                    .append(snippet.title())
                    .append(" (")
                    .append(snippet.source())
                    .append("): ")
                    .append(snippet.content())
                    .append("\n");
            }
            prompt.append("\n");
        }

        if (!turns.isEmpty()) {
            prompt.append("[Recent Conversation]\n");
            for (ConversationTurn turn : turns) {
                prompt.append("- ").append(turn.role()).append(": ").append(turn.content()).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("Requirements:\n");
        prompt.append("1) Output language must be Simplified Chinese.\n");
        prompt.append("2) Use grounded facts only, do not fabricate numbers.\n");
        if ("stock".equals(assistantType)) {
            prompt.append("3) Include risk warning: 投资有风险，决策需谨慎.\n");
        }
        prompt.append("4) Return strict JSON only (no markdown), schema:\n");
        prompt.append("{\"answer\":\"string\",\"warnings\":[\"string\"],\"sources\":[\"string\"],\"actions\":[\"string\"]}\n");
        prompt.append("5) In `answer`, use sections: 结论 / 关键数据 / 建议.\n");
        return prompt.toString();
    }

    private StructuredAnswerParser.ParsedAnswer parseOrRepairStructuredAnswer(
        String systemPrompt,
        String originalPrompt,
        String rawAnswer,
        String traceId,
        String assistantType,
        String question,
        String toolPayload,
        String modelOverride
    ) {
        StructuredAnswerParser.ParsedAnswer parsed = structuredAnswerParser.parse(rawAnswer).orElse(null);
        if (parsed != null) {
            return parsed;
        }

        log.warn("Structured answer parse failed traceId={} stage=primary preview={}", traceId, preview(rawAnswer));
        String repairPrompt = buildRepairPrompt(originalPrompt, rawAnswer);
        String repairedRawAnswer = aiGateway.chat(strictJsonRepairSystemPrompt(), repairPrompt, modelOverride, assistantType);
        StructuredAnswerParser.ParsedAnswer repaired = structuredAnswerParser.parse(repairedRawAnswer).orElse(null);
        if (repaired != null) {
            List<String> repairedWarnings = new ArrayList<>(repaired.warnings());
            repairedWarnings.add("schema_repair_retry");
            log.info("Structured answer repaired traceId={} stage=repair-success", traceId);
            return new StructuredAnswerParser.ParsedAnswer(repaired.answer(), repairedWarnings, repaired.sources(), repaired.actions());
        }

        log.warn("Structured answer parse failed traceId={} stage=repair preview={}", traceId, preview(repairedRawAnswer));
        StructuredAnswerParser.ParsedAnswer deterministicFallback = buildDeterministicFallback(rawAnswer, assistantType, question, toolPayload);
        if (deterministicFallback != null) {
            return deterministicFallback;
        }

        String fallbackAnswer = sanitizeRawAnswer(rawAnswer, assistantType);
        return new StructuredAnswerParser.ParsedAnswer(fallbackAnswer, List.of("schema_parse_failed"), List.of(), List.of());
    }

    private String rewriteForQuality(
        String assistantType,
        String question,
        String answer,
        List<String> qualityWarnings,
        String toolPayload,
        String systemPrompt,
        String modelOverride
    ) {
        try {
            String rewritePrompt = buildQualityRewritePrompt(assistantType, question, answer, qualityWarnings, toolPayload);
            String rewrittenRaw = aiGateway.chat(systemPrompt, rewritePrompt, modelOverride, assistantType);
            StructuredAnswerParser.ParsedAnswer rewritten = structuredAnswerParser.parse(rewrittenRaw).orElse(null);
            return rewritten != null ? rewritten.answer() : null;
        } catch (Exception ex) {
            log.warn("Quality rewrite failed: {}", ex.getMessage());
            return null;
        }
    }

    private String buildQualityRewritePrompt(
        String assistantType,
        String question,
        String answer,
        List<String> qualityWarnings,
        String toolPayload
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Rewrite answer to fix quality warnings.\n");
        prompt.append("assistantType=").append(assistantType).append("\n");
        prompt.append("question=").append(question).append("\n");
        prompt.append("qualityWarnings=").append(qualityWarnings).append("\n\n");
        prompt.append("originalAnswer=\n").append(answer).append("\n\n");
        if (toolPayload != null && !toolPayload.isBlank()) {
            prompt.append("toolPayload=\n").append(toolPayload).append("\n\n");
        }
        prompt.append("Return strict JSON only with schema:\n");
        prompt.append("{\"answer\":\"string\",\"warnings\":[],\"sources\":[],\"actions\":[]}\n");
        prompt.append("Answer format must include sections: 结论 / 关键数据 / 建议.");
        return prompt.toString();
    }

    private String buildRepairPrompt(String originalPrompt, String rawAnswer) {
        return "Reformat the model answer into strict JSON only. "
            + "Schema: {\"answer\":\"string\",\"warnings\":[\"string\"],\"sources\":[\"string\"],\"actions\":[\"string\"]}. "
            + "Do not include markdown code fences.\n\n"
            + "Original prompt:\n"
            + originalPrompt
            + "\n\nRaw answer:\n"
            + rawAnswer;
    }

    private String strictJsonRepairSystemPrompt() {
        return "You are a strict JSON formatter. Return JSON only. Do not include markdown, explanations or extra keys.";
    }

    private StructuredAnswerParser.ParsedAnswer buildDeterministicFallback(
        String rawAnswer,
        String assistantType,
        String question,
        String toolPayload
    ) {
        if (!isGatewayErrorLike(rawAnswer)) {
            return null;
        }

        String toolSummary = buildToolSummaryFallback(assistantType, toolPayload);
        if (toolSummary != null) {
            return new StructuredAnswerParser.ParsedAnswer(toolSummary, List.of(), List.of(), List.of("tool_template_fallback"));
        }

        return new StructuredAnswerParser.ParsedAnswer(buildQuestionBasedFallback(assistantType, question), List.of(), List.of(), List.of());
    }

    private String buildQuestionBasedFallback(String assistantType, String question) {
        if ("stock".equals(assistantType)) {
            return """
                已切换到稳健回答策略，先给你可执行建议。
                - 状态：当前采用工具与模板组合输出
                - 建议：稍后重试获取更完整的行情分析
                - 风险提示：投资有风险，决策需谨慎
                """;
        }

        String text = question == null ? "" : question.toLowerCase();
        if (containsAny(text, "budget", "expense", "saving", "预算", "支出", "节流", "省钱")) {
            return """
                我先给你一个可落地的财务建议版本。
                - 关键数据：优先关注餐饮、娱乐、冲动消费三类可变支出
                - 关键数据：建议每周固定复盘一次，观察预算偏离
                - 建议：先设置每周非必要支出上限，再持续记录 7 天
                - 建议：本月按“先控可变，再看固定”顺序优化
                """;
        }
        return GATEWAY_UNAVAILABLE_ANSWER;
    }

    private String buildToolSummaryFallback(String assistantType, String toolPayload) {
        if (toolPayload == null || toolPayload.isBlank()) {
            return null;
        }
        if ("finance".equals(assistantType)) {
            return buildFinanceToolSummary(toolPayload);
        }
        if ("stock".equals(assistantType)) {
            return """
                已获取工具数据，先给你稳定版结论。
                - 关键数据：行情工具数据已返回
                - 建议：稍后重试可获得更完整的结构化分析
                - 风险提示：投资有风险，决策需谨慎
                """;
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
        return """
            已获取财务工具数据，先给你稳定版摘要。
            - 关键数据：模型结构化失败，已启用本地模板
            - 建议：稍后重试可获得更完整的分析细节
            """;
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
        builder.append("预算执行情况如下。\n");
        builder.append("- 预算名称：").append(name).append("\n");
        builder.append("- 预算金额：").append(formatAmount(budgetAmount)).append(" 元\n");
        builder.append("- 已支出：").append(formatAmount(spent)).append(" 元\n");
        builder.append("- 剩余额度：").append(formatAmount(remaining)).append(" 元\n");
        builder.append("- 执行率：").append(formatPercent(usageRate)).append("\n");
        if (!period.isBlank()) {
            builder.append("- 周期：").append(period).append("\n");
        }
        builder.append("- 建议：").append(describeBudgetStatus(status));
        return builder.toString();
    }

    private String incomeExpenseSummary(JsonNode node) {
        BigDecimal totalIncome = decimal(node, "totalIncome");
        BigDecimal totalExpense = decimal(node, "totalExpense");
        BigDecimal balance = decimal(node, "balance");
        String period = text(node, "period", "");

        BigDecimal ratioBase = totalIncome.compareTo(BigDecimal.ZERO) > 0 ? totalIncome : BigDecimal.ONE;
        BigDecimal expenseRatio = totalExpense.divide(ratioBase, 4, RoundingMode.HALF_UP);
        String advice = expenseRatio.compareTo(new BigDecimal("0.85")) >= 0
            ? "支出占比偏高，建议优先收紧非必要消费。"
            : "收支结构整体平稳，保持每周复盘节奏。";

        StringBuilder builder = new StringBuilder();
        builder.append("收支汇总如下。\n");
        builder.append("- 总收入：").append(formatAmount(totalIncome)).append(" 元\n");
        builder.append("- 总支出：").append(formatAmount(totalExpense)).append(" 元\n");
        builder.append("- 结余：").append(formatAmount(balance)).append(" 元\n");
        if (!period.isBlank()) {
            builder.append("- 周期：").append(period).append("\n");
        }
        builder.append("- 支出/收入比：")
            .append(formatPercent(expenseRatio.multiply(new BigDecimal("100")).doubleValue())).append("\n");
        builder.append("- 建议：").append(advice);
        return builder.toString();
    }

    private String categorySummary(JsonNode node) {
        JsonNode categories = node.path("categories");
        StringBuilder builder = new StringBuilder();
        builder.append("分类占比概览如下。\n");
        int limit = Math.min(3, categories.size());
        for (int i = 0; i < limit; i++) {
            JsonNode item = categories.get(i);
            builder.append("- ")
                .append(text(item, "categoryName", "未分类"))
                .append("：")
                .append(formatAmount(decimal(item, "amount")))
                .append(" 元")
                .append(" (")
                .append(formatPercent(item.path("percentage").asDouble(0D)))
                .append(")\n");
        }
        builder.append("- 建议：给前 1~2 个高占比分类设置硬上限。");
        return builder.toString();
    }

    private String transactionSummary(JsonNode node) {
        int count = node.path("count").asInt(0);
        JsonNode txs = node.path("transactions");
        StringBuilder builder = new StringBuilder();
        builder.append("最近流水已获取。\n");
        builder.append("- 总笔数：").append(count).append("\n");

        if (txs.isArray() && txs.size() > 0) {
            int recentLimit = Math.min(5, txs.size());
            builder.append("- 最近").append(recentLimit).append("笔：");
            for (int i = 0; i < recentLimit; i++) {
                JsonNode tx = txs.get(i);
                String category = text(tx, "category", "未分类");
                String date = text(tx, "date", "--");
                BigDecimal amount = decimal(tx, "amount");
                if (i > 0) {
                    builder.append("；");
                }
                builder.append(date).append(" ").append(category).append(" ").append(formatAmount(amount));
            }
            builder.append("\n");

            List<JsonNode> ranked = new ArrayList<>();
            txs.forEach(ranked::add);
            ranked.sort((left, right) -> decimal(right, "amount").compareTo(decimal(left, "amount")));
            int topLimit = Math.min(2, ranked.size());
            builder.append("- 最大金额前").append(topLimit).append("笔：");
            for (int i = 0; i < topLimit; i++) {
                JsonNode tx = ranked.get(i);
                String category = text(tx, "category", "未分类");
                String date = text(tx, "date", "--");
                BigDecimal amount = decimal(tx, "amount");
                if (i > 0) {
                    builder.append("；");
                }
                builder.append(date).append(" ").append(category).append(" ").append(formatAmount(amount));
            }
            builder.append("\n");
        }

        builder.append("- 建议：关注大额且重复出现的可变支出项。");
        return builder.toString();
    }

    private String describeBudgetStatus(String status) {
        return switch (status) {
            case "over" -> "预算已超支，建议立即压缩非必要消费。";
            case "warning" -> "预算接近上限，建议本周控制可变支出。";
            default -> "预算整体在可控区间，保持每周跟踪。";
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
            || normalized.equals("Sorry, AI service is temporarily unavailable. Please try again later.")
            || normalized.equals("抱歉，AI 服务暂时不可用，请稍后再试。");
    }

    private String sanitizeRawAnswer(String rawAnswer, String assistantType) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return GATEWAY_UNAVAILABLE_ANSWER;
        }
        String trimmed = rawAnswer.trim();
        if (trimmed.contains("结论：") || trimmed.contains("关键数据：") || trimmed.contains("建议：")) {
            return trimmed;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("收到非结构化模型输出，已转为可读摘要。\n");
        builder.append("- 输出长度：").append(trimmed.length()).append("\n");
        builder.append("- 建议：稍后重试，或让我改为工具优先模式回答。\n");
        if ("stock".equals(assistantType)) {
            builder.append("- 风险提示：投资有风险，决策需谨慎");
        }
        return builder.toString();
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
        FinanceIntentClassifier.FinanceIntent intent = financeIntentClassifier.classify(message);
        return switch (intent.type()) {
            case BUDGET -> new ToolPlan("finance", mapOf("operation", "query_budget"));
            case CATEGORY -> new ToolPlan(
                "finance",
                mapOf("operation", "query_category_stats", "type", intent.transactionType() != null ? intent.transactionType() : 2)
            );
            case TRANSACTION -> new ToolPlan(
                "finance",
                intent.transactionType() != null
                    ? mapOf("operation", "query_transactions", "type", intent.transactionType())
                    : mapOf("operation", "query_transactions")
            );
            case CASHFLOW -> new ToolPlan("finance", mapOf("operation", "query_income_expense"));
            case UNKNOWN -> null;
        };
    }

    private ToolPlan chooseStockPlan(String message) {
        String lower = message == null ? "" : message.toLowerCase();
        if (containsAny(lower, "market", "index", "大盘", "指数")) {
            return new ToolPlan("stock", mapOf("operation", "query_market_index"));
        }
        if (containsAny(lower, "news", "新闻", "资讯")) {
            String code = extractStockCode(message);
            if (code != null) {
                return new ToolPlan("stock", mapOf("operation", "get_stock_news", "stockCode", code));
            }
            return new ToolPlan("stock", mapOf("operation", "query_market_index"));
        }
        if (containsAny(lower, "search", "搜索", "查找")) {
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
        return "stock".equalsIgnoreCase(assistantType) ? "stock" : "finance";
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
            if (text.contains(keyword.toLowerCase())) {
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

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private List<String> deduplicate(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                unique.add(value);
            }
        }
        return List.copyOf(unique);
    }

    private record ToolPlan(String domain, Map<String, Object> params) {
    }
}
