package com.mamoji.agent;

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
                    return buildErrorResponse("抱歉，工具调用失败，请稍后再试。", warnings, actions, sources, message);
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
                traceId
            );
            String answer = parsed.answer();
            warnings.addAll(parsed.warnings());
            sources.addAll(parsed.sources());
            actions.addAll(parsed.actions());

            memoryService.append(sessionKey, "user", message);
            memoryService.append(sessionKey, "assistant", answer);

            warnings.addAll(qualityGateService.validate(type, message, answer));
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
            return buildErrorResponse("抱歉，处理请求时发生错误，请稍后再试。", List.of("internal_error"), List.of(), List.of(), message);
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
        prompt.append("Return JSON only with schema: ")
            .append("{\"answer\":\"string\",\"warnings\":[\"string\"],\"sources\":[\"string\"],\"actions\":[\"string\"]}.");
        return prompt.toString();
    }

    private StructuredAnswerParser.ParsedAnswer parseOrRepairStructuredAnswer(
        String systemPrompt,
        String originalPrompt,
        String rawAnswer,
        String traceId
    ) {
        StructuredAnswerParser.ParsedAnswer parsed = structuredAnswerParser.parse(rawAnswer).orElse(null);
        if (parsed != null) {
            return parsed;
        }

        log.warn("Structured answer parse failed traceId={} stage=primary preview={}", traceId, preview(rawAnswer));
        String repairPrompt = buildRepairPrompt(originalPrompt, rawAnswer);
        String repairedRawAnswer = aiGateway.chat(systemPrompt, repairPrompt, null, null);
        StructuredAnswerParser.ParsedAnswer repaired = structuredAnswerParser.parse(repairedRawAnswer).orElse(null);
        if (repaired != null) {
            List<String> warnings = new ArrayList<>(repaired.warnings());
            warnings.add("schema_repair_retry");
            log.info("Structured answer repaired traceId={} stage=repair-success", traceId);
            return new StructuredAnswerParser.ParsedAnswer(repaired.answer(), warnings, repaired.sources(), repaired.actions());
        }

        log.warn("Structured answer parse failed traceId={} stage=repair preview={}", traceId, preview(repairedRawAnswer));
        return new StructuredAnswerParser.ParsedAnswer(
            sanitizeRawAnswer(rawAnswer),
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

    private ToolPlan chooseToolPlan(String assistantType, String message) {
        if ("stock".equals(assistantType)) {
            return chooseStockPlan(message);
        }
        return chooseFinancePlan(message);
    }

    private ToolPlan chooseFinancePlan(String message) {
        String lower = message.toLowerCase();
        if (containsAny(lower, "预算", "budget")) {
            return new ToolPlan("finance", mapOf("operation", "query_budget"));
        }
        if (containsAny(lower, "分类", "category", "占比")) {
            return new ToolPlan("finance", mapOf("operation", "query_category_stats", "type", 2));
        }
        if (containsAny(lower, "交易", "记录", "流水")) {
            return new ToolPlan("finance", mapOf("operation", "query_transactions"));
        }
        if (containsAny(lower, "支出", "收入", "收支", "开销")) {
            return new ToolPlan("finance", mapOf("operation", "query_income_expense"));
        }
        return null;
    }

    private ToolPlan chooseStockPlan(String message) {
        String lower = message.toLowerCase();
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
        return (assistantType == null || assistantType.isBlank()) ? "finance" : assistantType;
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
