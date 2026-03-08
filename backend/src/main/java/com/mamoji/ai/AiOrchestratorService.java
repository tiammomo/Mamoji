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

    private static final List<String> STOCK_AGENT_HINTS = List.of("news", "quote", "index", "market", "stock", "行情", "指数", "新闻");
    private static final List<String> FINANCE_AGENT_HINTS = List.of(
        "budget",
        "transaction",
        "expense",
        "income",
        "category",
        "stats",
        "预算",
        "流水",
        "收支",
        "分类"
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
        AiChatMode mode = AiChatMode.from(requestedMode);
        AiChatMode modeUsed = mode == AiChatMode.AUTO ? selectAutoMode(type, message) : mode;

        aiMetricsService.recordChatMode(mode.value(), modeUsed.value(), type);

        if (modeUsed == AiChatMode.LLM) {
            return llmResponse(userId, message, type, modeUsed, traceId, null);
        }

        StructuredAiResponse agentResponse = safeAgentCall(userId, message, type, sessionId);
        if (!isAgentFailure(agentResponse)) {
            return withMeta(agentResponse, modeUsed.value(), traceId);
        }

        aiMetricsService.recordChatModeFallback("agent", "llm", "agent_failed");
        return llmResponse(userId, message, type, AiChatMode.LLM, traceId, agentResponse.warnings());
    }

    private StructuredAiResponse safeAgentCall(Long userId, String message, String assistantType, String sessionId) {
        try {
            return reActAgentService.processMessageStructured(userId, message, assistantType, sessionId);
        } catch (Exception ex) {
            return new StructuredAiResponse(
                "Agent execution failed",
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
        usage.put("inputChars", message == null ? 0 : message.length());
        usage.put("outputChars", answer.length());
        usage.put("estimatedTokens", Math.max(1, ((message == null ? 0 : message.length()) + answer.length()) / 4));

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
        for (String warning : response.warnings()) {
            if (warning == null) {
                continue;
            }
            if (warning.contains("internal_error") || warning.contains("Tool call failed")) {
                return true;
            }
        }
        return false;
    }

    private AiChatMode selectAutoMode(String assistantType, String message) {
        String content = message == null ? "" : message;
        String lower = content.toLowerCase();

        if ("stock".equals(assistantType)) {
            if (containsSixDigitCode(content) || containsAny(lower, STOCK_AGENT_HINTS)) {
                return AiChatMode.AGENT;
            }
            return AiChatMode.LLM;
        }

        if (containsAny(lower, FINANCE_AGENT_HINTS)) {
            return AiChatMode.AGENT;
        }
        return AiChatMode.LLM;
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

    private String normalizeAssistantType(String assistantType) {
        if (assistantType == null || assistantType.isBlank()) {
            return "finance";
        }
        return assistantType.trim().toLowerCase();
    }
}
