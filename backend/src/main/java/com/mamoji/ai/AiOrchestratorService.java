package com.mamoji.ai;

import com.mamoji.agent.ReActAgentService;
import com.mamoji.ai.metrics.AiMetricsService;
import com.mamoji.ai.model.StructuredAiResponse;
import com.mamoji.dto.AIChatResponse;
import com.mamoji.service.AIService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiOrchestratorService {

    private static final List<String> STOCK_AGENT_HINTS = List.of(
        "stock", "quote", "index", "market", "news", "kline",
        "股票", "个股", "行情", "报价", "指数", "大盘", "新闻", "k线"
    );
    private static final List<String> FINANCE_AGENT_HINTS = List.of(
        "budget", "transaction", "expense", "income", "category", "cashflow", "saving",
        "预算", "流水", "收支", "支出", "收入", "分类", "消费", "开销", "记账", "结余", "节流", "省钱"
    );

    private final AIService aiService;
    private final ReActAgentService reActAgentService;
    private final AiMetricsService aiMetricsService;

    public StructuredAiResponse chatStructured(
        Long userId,
        String message,
        String assistantType,
        String sessionId,
        String requestedMode
    ) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        String type = normalizeAssistantType(assistantType);
        String safeMessage = message == null ? "" : message.trim();
        AiChatMode mode = AiChatMode.from(requestedMode);
        AiChatMode modeUsed = mode == AiChatMode.AUTO ? selectAutoMode(type, safeMessage) : mode;

        aiMetricsService.recordChatMode(mode.value(), modeUsed.value(), type);

        if (modeUsed == AiChatMode.LLM) {
            return llmResponse(userId, safeMessage, type, modeUsed, traceId, null);
        }

        StructuredAiResponse agentResponse = safeAgentCall(userId, safeMessage, type, sessionId);
        if (!isAgentFailure(agentResponse)) {
            return withMeta(agentResponse, modeUsed.value(), traceId);
        }

        aiMetricsService.recordChatModeFallback("agent", "llm", "agent_failed");
        return llmResponse(userId, safeMessage, type, AiChatMode.LLM, traceId, agentResponse.warnings());
    }

    private StructuredAiResponse safeAgentCall(Long userId, String message, String assistantType, String sessionId) {
        try {
            return reActAgentService.processMessageStructured(userId, message, assistantType, sessionId);
        } catch (Exception ex) {
            return new StructuredAiResponse(
                "抱歉，系统处理请求时出现异常，请稍后重试。",
                List.of(),
                List.of(),
                List.of("internal_error"),
                Map.of()
            );
        }
    }

    private StructuredAiResponse llmResponse(
        Long userId,
        String message,
        String assistantType,
        AiChatMode modeUsed,
        String traceId,
        List<String> inheritedWarnings
    ) {
        AIChatResponse response = aiService.chat(userId, message, assistantType);
        String answer = response.getReply() == null ? "" : response.getReply();
        List<String> warnings = new ArrayList<>();
        if (inheritedWarnings != null && !inheritedWarnings.isEmpty()) {
            warnings.addAll(inheritedWarnings);
            if (!warnings.contains("agent_failed_fallback_to_llm")) {
                warnings.add("agent_failed_fallback_to_llm");
            }
        }

        Map<String, Object> usage = new HashMap<>();
        usage.put("inputChars", message.length());
        usage.put("outputChars", answer.length());
        usage.put("estimatedTokens", Math.max(1, (message.length() + answer.length()) / 4));

        return new StructuredAiResponse(
            answer,
            List.of(),
            List.of(),
            warnings,
            usage,
            modeUsed.value(),
            traceId
        );
    }

    private StructuredAiResponse withMeta(StructuredAiResponse response, String modeUsed, String traceId) {
        return new StructuredAiResponse(
            response.answer(),
            response.sources(),
            response.actions(),
            response.warnings(),
            response.usage(),
            modeUsed,
            traceId
        );
    }

    private boolean isAgentFailure(StructuredAiResponse response) {
        if (response == null) {
            return true;
        }
        if (response.answer() == null || response.answer().isBlank()) {
            return true;
        }
        for (String warning : response.warnings()) {
            if (warning == null) {
                continue;
            }
            if (warning.contains("internal_error") || warning.contains("tool_call_failed")) {
                return true;
            }
        }
        return false;
    }

    private AiChatMode selectAutoMode(String assistantType, String message) {
        String lower = message.toLowerCase();
        if ("stock".equals(assistantType)) {
            if (containsSixDigitCode(message) || containsAny(lower, STOCK_AGENT_HINTS)) {
                return AiChatMode.AGENT;
            }
            return AiChatMode.LLM;
        }

        // Finance assistant defaults to AGENT for grounded answers.
        if (message.isBlank()) {
            return AiChatMode.LLM;
        }
        if (containsAny(lower, FINANCE_AGENT_HINTS) || containsAmount(message) || containsDateHint(message)) {
            return AiChatMode.AGENT;
        }
        return AiChatMode.AGENT;
    }

    private boolean containsAny(String text, List<String> candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSixDigitCode(String text) {
        return text != null && text.matches(".*\\b\\d{6}\\b.*");
    }

    private boolean containsAmount(String text) {
        if (text == null) {
            return false;
        }
        return text.matches(".*\\d+(?:\\.\\d+)?\\s*(元|块|万元|w|k).*")
            || text.matches(".*[¥￥]\\s*\\d+(?:\\.\\d+)?.*");
    }

    private boolean containsDateHint(String text) {
        if (text == null) {
            return false;
        }
        return text.contains("本月")
            || text.contains("本年")
            || text.contains("今年")
            || text.contains("上月")
            || text.contains("最近")
            || text.matches(".*\\d{4}-\\d{1,2}(-\\d{1,2})?.*");
    }

    private String normalizeAssistantType(String assistantType) {
        if ("stock".equalsIgnoreCase(assistantType)) {
            return "stock";
        }
        return "finance";
    }
}
