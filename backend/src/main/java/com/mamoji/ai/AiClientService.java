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
public class AiClientService {

    private static final String CHAT_PATH = "/v1/text/chatcompletion_v2";
    private static final Duration STREAM_CHUNK_DELAY = Duration.ofMillis(40);
    private static final String DEFAULT_MODEL = "abab6.5s-chat";
    private static final String FALLBACK_ERROR_MESSAGE = "Sorry, AI service is temporarily unavailable. Please try again later.";

    private final AiProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final AiMetricsService metricsService;

    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, null, null);
    }

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
     * Current provider endpoint is non-streaming; this method provides a stable SSE contract
     * by chunking the final reply on server side.
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

    private Mono<String> chatMono(String systemPrompt, String userPrompt, String traceId, String model) {
        return webClientBuilder
            .baseUrl(properties.getBaseUrl())
            .defaultHeader("Authorization", "Bearer " + safeToken(properties.getApiKey()))
            .defaultHeader("X-Trace-Id", traceId)
            .build()
            .post()
            .uri(CHAT_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(buildRequestBody(systemPrompt, userPrompt, model))
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

    private Map<String, Object> buildRequestBody(String systemPrompt, String userPrompt, String model) {
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
            return "AI service returned invalid response format";
        }

        Object firstChoice = choices.get(0);
        if (!(firstChoice instanceof Map<?, ?> choiceMap)) {
            return "AI service returned invalid response choice";
        }

        Object messageObj = choiceMap.get("message");
        if (!(messageObj instanceof Map<?, ?> messageMap)) {
            return "AI service returned invalid response message";
        }

        Object content = messageMap.get("content");
        return content != null ? content.toString() : "AI service returned empty message";
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
