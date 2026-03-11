package com.mamoji.ai;

import com.mamoji.ai.metrics.AiMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * AI 客户端调用层。
 *
 * <p>封装上游模型调用、失败重试、主备模型切换与统一错误兜底。
 */
public class AiClientService {

    private static final String CHAT_PATH = "/v1/text/chatcompletion_v2";
    private static final String ANTHROPIC_CHAT_PATH = "/v1/messages";
    private static final Duration STREAM_CHUNK_DELAY = Duration.ofMillis(40);
    private static final String DEFAULT_MODEL = "abab6.5s-chat";
    private static final String FALLBACK_ERROR_MESSAGE = "Sorry, AI service is temporarily unavailable. Please try again later.";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final AiProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final AiMetricsService metricsService;

    /**
     * 默认模型调用入口。
     */
    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, null, null);
    }

    /**
     * AI 同步调用入口（支持模型覆盖）。
     */
    public String chat(String systemPrompt, String userPrompt, String modelOverride, String assistantType) {
        String traceId = shortTraceId();
        long start = System.currentTimeMillis();

        try {
            String result = chatWithFallbackMono(systemPrompt, userPrompt, traceId, modelOverride).block();
            long elapsed = System.currentTimeMillis() - start;
            log.info("AI request success traceId={} elapsedMs={}", traceId, elapsed);
            metricsService.recordRequest("minimaxi", true, elapsed, estimateTokens(systemPrompt, userPrompt, result));
            metricsService.recordModelRoute(assistantType, normalizeModel(modelOverride != null ? modelOverride : properties.getModel()));
            return result != null ? result : "AI service returned empty response";
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("AI request failed traceId={} elapsedMs={} error={}", traceId, elapsed, ex.getMessage(), ex);
            metricsService.recordRequest("minimaxi", false, elapsed, estimateTokens(systemPrompt, userPrompt, null));
            return FALLBACK_ERROR_MESSAGE;
        }
    }

    /**
     * 流式返回入口：当前上游为非流式接口，这里通过服务端分块模拟稳定 SSE 契约。
     */
    public Flux<String> streamChat(String systemPrompt, String userPrompt) {
        String traceId = shortTraceId();
        return chatWithFallbackMono(systemPrompt, userPrompt, traceId, null)
            .flatMapMany(this::chunkText)
            .onErrorResume(ex -> {
                log.error("AI stream failed traceId={} error={}", traceId, ex.getMessage(), ex);
                return Flux.just(FALLBACK_ERROR_MESSAGE);
            });
    }

    /**
     * 主备模型调用编排：
     * primary 失败时按配置回退到 fallback。
     */
    private Mono<String> chatWithFallbackMono(String systemPrompt, String userPrompt, String traceId, String modelOverride) {
        String primaryModel = normalizeModel(modelOverride != null ? modelOverride : properties.getModel());
        String fallbackModel = normalizeOptionalModel(properties.getFallbackModel());

        Mono<String> primary = chatMono(systemPrompt, userPrompt, traceId, primaryModel);
        if (!shouldUseFallback(primaryModel, fallbackModel)) {
            return primary;
        }

        return primary.onErrorResume(ex -> {
            log.warn(
                "AI primary model failed, switching to fallback traceId={} primaryModel={} fallbackModel={} error={}",
                traceId,
                primaryModel,
                fallbackModel,
                ex.getMessage()
            );
            metricsService.recordModelFallback(primaryModel, fallbackModel);
            return chatMono(systemPrompt, userPrompt, traceId, fallbackModel);
        });
    }

    /**
     * 单模型调用（带超时与瞬时错误重试）。
     */
    private Mono<String> chatMono(String systemPrompt, String userPrompt, String traceId, String model) {
        boolean anthropicMode = isAnthropicMode();
        WebClient client = webClientBuilder
            .baseUrl(properties.getBaseUrl())
            .defaultHeader("X-Trace-Id", traceId)
            .build();

        WebClient.RequestBodySpec requestBodySpec = client
            .post()
            .uri(anthropicMode ? ANTHROPIC_CHAT_PATH : CHAT_PATH)
            .contentType(MediaType.APPLICATION_JSON);

        if (anthropicMode) {
            requestBodySpec.header("x-api-key", safeToken(properties.getApiKey()));
            requestBodySpec.header("anthropic-version", ANTHROPIC_VERSION);
        } else {
            requestBodySpec.header("Authorization", "Bearer " + safeToken(properties.getApiKey()));
        }

        return requestBodySpec
            .bodyValue(buildRequestBody(systemPrompt, userPrompt, model, anthropicMode))
            .retrieve()
            .bodyToMono(Map.class)
            .map(this::extractReply)
            .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
            .retryWhen(
                Retry.backoff(properties.getMaxRetries(), Duration.ofMillis(250))
                    .maxBackoff(Duration.ofSeconds(3))
                    .filter(this::isTransientError)
            );
    }

    private Map<String, Object> buildRequestBody(String systemPrompt, String userPrompt, String model, boolean anthropicMode) {
        if (anthropicMode) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("max_tokens", properties.getMaxTokens());
            payload.put("temperature", properties.getTemperature());
            payload.put("system", systemPrompt);
            payload.put("messages", List.of(
                Map.of("role", "user", "content", userPrompt)
            ));
            return payload;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("max_tokens", properties.getMaxTokens());
        payload.put("temperature", properties.getTemperature());
        payload.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userPrompt)
        ));
        return payload;
    }

    @SuppressWarnings("unchecked")
    private String extractReply(Map<String, Object> response) {
        if (response == null) {
            return "AI service returned empty response";
        }

        Object error = response.get("error");
        if (error instanceof Map<?, ?> errorMap) {
            Object message = errorMap.get("message");
            return "AI service error: " + (message != null ? message : "unknown");
        }

        Object choicesObj = response.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            String directReply = extractFirstString(response, "reply", "text", "output_text", "outputText", "result");
            if (directReply != null) {
                return directReply;
            }

            String outputText = extractTextFromContent(response.get("output"));
            if (outputText != null) {
                return outputText;
            }

            Object contentObj = response.get("content");
            String topContent = extractTextFromContent(contentObj);
            if (topContent != null) {
                return topContent;
            }

            Object dataObj = response.get("data");
            if (dataObj instanceof Map<?, ?> dataMap) {
                String nestedReply = extractFirstString(dataMap, "reply", "text", "output_text", "outputText", "result");
                if (nestedReply != null) {
                    return nestedReply;
                }
                String nestedContent = extractTextFromContent(dataMap.get("content"));
                if (nestedContent != null) {
                    return nestedContent;
                }

                String nestedOutput = extractTextFromContent(dataMap.get("output"));
                if (nestedOutput != null) {
                    return nestedOutput;
                }
            }

            log.warn("AI reply format not recognized, keys={}", response.keySet());
            return "AI service returned invalid response format";
        }

        Object firstChoice = choices.get(0);
        if (!(firstChoice instanceof Map<?, ?> choiceMap)) {
            return "AI service returned invalid response choice";
        }

        String choiceContent = extractChoiceText(choiceMap);
        if (choiceContent != null) {
            return choiceContent;
        }

        Object messageObj = choiceMap.get("message");
        if (messageObj instanceof Map<?, ?> messageMap) {
            Object content = messageMap.get("content");
            String normalized = extractTextFromContent(content);
            if (normalized != null) {
                return normalized;
            }
            return "AI service returned empty message";
        }

        Object contentObj = choiceMap.get("content");
        String normalized = extractTextFromContent(contentObj);
        if (normalized != null) {
            return normalized;
        }

        log.warn("AI reply choice format not recognized, keys={}", choiceMap.keySet());
        return "AI service returned invalid response message";
    }

    private String extractChoiceText(Map<?, ?> choiceMap) {
        String direct = extractFirstString(choiceMap, "text", "output_text", "outputText", "reply", "result");
        if (direct != null) {
            return direct;
        }

        Object output = choiceMap.get("output");
        String outputText = extractTextFromContent(output);
        if (outputText != null) {
            return outputText;
        }

        Object delta = choiceMap.get("delta");
        String deltaText = extractTextFromContent(delta);
        if (deltaText != null) {
            return deltaText;
        }

        Object messagesObj = choiceMap.get("messages");
        if (messagesObj instanceof List<?> messages && !messages.isEmpty()) {
            Object firstMessage = messages.get(0);
            if (firstMessage instanceof Map<?, ?> messageMap) {
                String msgText = extractTextFromContent(messageMap.get("content"));
                if (msgText != null) {
                    return msgText;
                }
                msgText = extractFirstString(messageMap, "text", "reply", "result");
                if (msgText != null) {
                    return msgText;
                }
            }
            return extractTextFromContent(firstMessage);
        }

        return null;
    }

    private String extractFirstString(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text.trim();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractTextFromContent(Object content) {
        if (content == null) {
            return null;
        }
        if (content instanceof String text) {
            return text.isBlank() ? null : text.trim();
        }
        if (content instanceof List<?> list) {
            StringBuilder builder = new StringBuilder();
            for (Object item : list) {
                if (item instanceof String part) {
                    if (!part.isBlank()) {
                        if (!builder.isEmpty()) {
                            builder.append('\n');
                        }
                        builder.append(part.trim());
                    }
                    continue;
                }
                if (item instanceof Map<?, ?> block) {
                    Object blockText = block.get("text");
                    if (!(blockText instanceof String textPart) || textPart.isBlank()) {
                        Object nestedContent = block.get("content");
                        String nested = extractTextFromContent(nestedContent);
                        if (nested != null) {
                            if (!builder.isEmpty()) {
                                builder.append('\n');
                            }
                            builder.append(nested);
                        }
                        continue;
                    }
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(textPart.trim());
                }
            }
            return builder.isEmpty() ? null : builder.toString();
        }
        if (content instanceof Map<?, ?> contentMap) {
            String text = extractFirstString(contentMap, "text", "content", "value");
            return text != null ? text : null;
        }
        return content.toString();
    }

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
            })
            .delayElements(STREAM_CHUNK_DELAY);
    }

    private boolean isTransientError(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (throwable instanceof java.util.concurrent.TimeoutException) {
            return true;
        }
        if (throwable instanceof IOException) {
            return true;
        }
        if (throwable instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            return isTransientError(cause);
        }
        return false;
    }

    private String shortTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String safeToken(String token) {
        return token != null ? token : "";
    }

    private boolean shouldUseFallback(String primaryModel, String fallbackModel) {
        return fallbackModel != null && !fallbackModel.isBlank() && !fallbackModel.equals(primaryModel);
    }

    private boolean isAnthropicMode() {
        String baseUrl = properties.getBaseUrl();
        return baseUrl != null && baseUrl.toLowerCase().contains("/anthropic");
    }

    private String normalizeOptionalModel(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        return normalizeModel(model);
    }

    private String normalizeModel(String model) {
        if (model == null || model.isBlank()) {
            return DEFAULT_MODEL;
        }
        return model.trim();
    }

    private int estimateTokens(String systemPrompt, String userPrompt, String output) {
        int chars = 0;
        chars += systemPrompt != null ? systemPrompt.length() : 0;
        chars += userPrompt != null ? userPrompt.length() : 0;
        chars += output != null ? output.length() : 0;
        return Math.max(1, chars / 4);
    }
}
