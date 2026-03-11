package com.mamoji.ai;

import com.mamoji.ai.metrics.AiMetricsService;
import com.mamoji.ai.tool.SpringAiToolCallingBridge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Spring AI based gateway implementation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnClass(ChatClient.class)
@ConditionalOnBean(ChatModel.class)
public class SpringAiGateway implements AiGateway {

    private static final String FALLBACK_ERROR_MESSAGE = "抱歉，AI 服务暂时不可用，请稍后再试。";

    private final ChatModel chatModel;
    private final AiMetricsService metricsService;
    private final SpringAiToolCallingBridge springAiToolCallingBridge;

    /**
     * Executes synchronous chat with optional tool-context enrichment.
     */
    @Override
    public String chat(String systemPrompt, String userPrompt, String modelOverride, String assistantType) {
        long start = System.currentTimeMillis();
        String safeSystemPrompt = systemPrompt == null ? "" : systemPrompt.trim();
        String safeUserPrompt = userPrompt == null ? "" : userPrompt.trim();

        try {
            SpringAiToolCallingBridge.ToolCallingContext toolContext = springAiToolCallingBridge.invoke(assistantType, safeUserPrompt);
            String fullPrompt = buildPrompt(safeSystemPrompt, safeUserPrompt, modelOverride, toolContext);

            String result = ChatClient.create(chatModel)
                .prompt(fullPrompt)
                .call()
                .content();

            long elapsed = System.currentTimeMillis() - start;
            metricsService.recordRequest("spring-ai", true, elapsed, estimateTokens(fullPrompt, result));
            metricsService.recordModelRoute(assistantType, modelOverride != null ? modelOverride : "spring-ai-default");

            if (!toolContext.warnings().isEmpty()) {
                log.warn("Spring tool-calling warnings assistantType={} warnings={}", assistantType, toolContext.warnings());
            }
            return result != null ? result : "AI service returned empty response";
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Spring AI request failed elapsedMs={} error={}", elapsed, ex.getMessage(), ex);
            metricsService.recordRequest("spring-ai", false, elapsed, estimateTokens(safeSystemPrompt, safeUserPrompt));
            return FALLBACK_ERROR_MESSAGE;
        }
    }

    /**
     * Provides chunked streaming over synchronous chat result.
     */
    @Override
    public Flux<String> streamChat(String systemPrompt, String userPrompt, String modelOverride, String assistantType) {
        return chunkText(chat(systemPrompt, userPrompt, modelOverride, assistantType));
    }

    /**
     * Builds consolidated prompt with system/model/tool context and user input.
     */
    private String buildPrompt(
        String systemPrompt,
        String userPrompt,
        String modelOverride,
        SpringAiToolCallingBridge.ToolCallingContext toolContext
    ) {
        StringBuilder prompt = new StringBuilder();
        if (!systemPrompt.isBlank()) {
            prompt.append("[System]\n").append(systemPrompt).append("\n\n");
        }
        if (modelOverride != null && !modelOverride.isBlank()) {
            prompt.append("[PreferredModel]\n").append(modelOverride.trim()).append("\n\n");
        }
        if (toolContext != null && toolContext.promptAddon() != null && !toolContext.promptAddon().isBlank()) {
            prompt.append("[ToolContext]\n").append(toolContext.promptAddon()).append("\n");
        }
        prompt.append("[User]\n").append(userPrompt).append("\n\n");
        prompt.append("请确保回答可执行、数据口径清晰、避免空泛描述。");
        return prompt.toString();
    }

    /**
     * Splits text into fixed-size chunks for SSE-like delivery.
     */
    private Flux<String> chunkText(String fullText) {
        if (fullText == null || fullText.isEmpty()) {
            return Flux.just("");
        }
        int chunkSize = 24;
        int count = (fullText.length() + chunkSize - 1) / chunkSize;
        return Flux.range(0, count)
            .map(i -> {
                int start = i * chunkSize;
                int end = Math.min(start + chunkSize, fullText.length());
                return fullText.substring(start, end);
            });
    }

    /**
     * Rough token estimation by character count.
     */
    private int estimateTokens(String input, String output) {
        int chars = 0;
        chars += input != null ? input.length() : 0;
        chars += output != null ? output.length() : 0;
        return Math.max(1, chars / 4);
    }
}
