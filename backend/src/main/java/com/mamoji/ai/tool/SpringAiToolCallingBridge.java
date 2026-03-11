package com.mamoji.ai.tool;

import com.mamoji.ai.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridge that injects tool-calling result into Spring AI prompt context.
 */
@Component
@RequiredArgsConstructor
public class SpringAiToolCallingBridge {

    private static final Long SPRING_AI_TOOL_USER_ID = 0L;

    private final AiProperties aiProperties;
    private final AiToolRouter aiToolRouter;

    /**
     * Executes eligible tool call and returns prompt addon/actions/warnings.
     */
    public ToolCallingContext invoke(String assistantType, String userPrompt) {
        AiProperties.ToolCallingOps ops = aiProperties.getToolCallingOps();
        if (!ops.isSpringEnabled()) {
            return ToolCallingContext.empty();
        }
        if (!"stock".equalsIgnoreCase(assistantType) || !ops.isStockEnabled()) {
            return ToolCallingContext.empty();
        }

        Map<String, Object> params = buildStockToolParams(userPrompt);
        if (params.isEmpty()) {
            return ToolCallingContext.empty();
        }

        AiToolResult result = aiToolRouter.route(SPRING_AI_TOOL_USER_ID, "stock", params);
        if (!result.success()) {
            return new ToolCallingContext(
                "",
                List.of(),
                List.of("spring_tool_call_failed:" + result.error())
            );
        }

        String promptAddon = "Tool result(JSON):\n" + result.payload() + "\n\n";
        return new ToolCallingContext(
            promptAddon,
            List.of(result.toolName()),
            List.of()
        );
    }

    /**
     * Infers stock tool operation from user message.
     */
    private Map<String, Object> buildStockToolParams(String message) {
        if (message == null || message.isBlank()) {
            return Map.of("operation", "query_market_index");
        }
        String lower = message.toLowerCase();
        if (containsAny(lower, "market", "index", "quote", "行情", "指数", "大盘")) {
            return Map.of("operation", "query_market_index");
        }
        if (containsAny(lower, "news", "资讯", "新闻")) {
            String code = extractStockCode(message);
            if (code != null) {
                return Map.of("operation", "get_stock_news", "stockCode", code);
            }
            return Map.of("operation", "query_market_index");
        }
        if (containsAny(lower, "search", "查找", "搜索")) {
            return Map.of("operation", "search_stock", "keyword", message);
        }

        String code = extractStockCode(message);
        if (code != null) {
            Map<String, Object> params = new HashMap<>();
            params.put("operation", "query_stock_quote");
            params.put("stockCode", code);
            return params;
        }

        return Map.of("operation", "query_market_index");
    }

    /**
     * Extracts six-digit stock code from text.
     */
    private String extractStockCode(String text) {
        if (text == null) {
            return null;
        }
        String digits = text.replaceAll(".*?(\\d{6}).*", "$1");
        return digits.matches("\\d{6}") ? digits : null;
    }

    /**
     * Checks whether text contains any keyword.
     */
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tool-calling context payload for prompt assembly.
     */
    public record ToolCallingContext(String promptAddon, List<String> actions, List<String> warnings) {
        /**
         * Empty context factory.
         */
        static ToolCallingContext empty() {
            return new ToolCallingContext("", List.of(), List.of());
        }
    }
}
