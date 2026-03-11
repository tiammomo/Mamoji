package com.mamoji.controller;

import com.mamoji.ai.AiOrchestratorService;
import com.mamoji.ai.AiProperties;
import com.mamoji.ai.model.StructuredAiResponse;
import com.mamoji.common.api.ApiResponses;
import com.mamoji.dto.AIChatRequest;
import com.mamoji.dto.AIChatResponse;
import com.mamoji.entity.User;
import com.mamoji.security.AuthenticationUser;
import com.mamoji.service.AIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP entry point for AI chat features.
 *
 * <p>This controller keeps endpoint compatibility for old and new clients:
 * <ul>
 *   <li>{@code /chat}: legacy response shape, only plain answer text.
 *   <li>{@code /chat/v2}: structured response including metadata.
 *   <li>{@code /chat/stream}: SSE streaming protocol for incremental rendering.
 *   <li>{@code /chat/legacy}: deprecated endpoint kept during migration window.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
@Slf4j
public class AIController {

    private static final String LEGACY_CHAT_SUNSET_DATE = "2026-04-30";
    private static final String LEGACY_CHAT_SUNSET_RFC1123 = "Thu, 30 Apr 2026 23:59:59 GMT";

    private final AIService aiService;
    private final AiOrchestratorService aiOrchestratorService;
    private final AiProperties aiProperties;

    /**
     * Legacy-compatible chat endpoint that returns only the plain answer field.
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody AIChatRequest request, @AuthenticationUser User user) {
        StructuredAiResponse response = requestStructuredResponse(request, user, request.getMode());
        return ApiResponses.ok(new AIChatResponse(response.answer()));
    }

    /**
     * Preferred chat endpoint that exposes structured answer payload.
     */
    @PostMapping("/chat/v2")
    public ResponseEntity<Map<String, Object>> chatV2(@RequestBody AIChatRequest request, @AuthenticationUser User user) {
        return ApiResponses.ok(requestStructuredResponse(request, user, request.getMode()));
    }

    /**
     * Deprecated endpoint retained temporarily for older clients.
     *
     * <p>Response headers include migration hints so clients can switch to
     * {@code /api/v1/ai/chat}.
     */
    @PostMapping("/chat/legacy")
    @Deprecated(forRemoval = true, since = "2026-03-08")
    public ResponseEntity<Map<String, Object>> chatLegacy(@RequestBody AIChatRequest request, @AuthenticationUser User user) {
        log.warn("Deprecated endpoint called userId={} endpoint=/api/v1/ai/chat/legacy", user.getId());
        AIChatResponse response = aiService.chat(user.getId(), request.getMessage(), request.getAssistantType());
        Map<String, Object> payload = ApiResponses.body(0, "success", response);
        payload.put("deprecation", Map.of(
            "endpoint", "/api/v1/ai/chat/legacy",
            "successor", "/api/v1/ai/chat",
            "sunsetDate", LEGACY_CHAT_SUNSET_DATE
        ));
        return ResponseEntity.ok()
            .header("Deprecation", "true")
            .header("Sunset", LEGACY_CHAT_SUNSET_RFC1123)
            .header("Link", "</api/v1/ai/chat>; rel=\"successor-version\"")
            .body(payload);
    }

    /**
     * Streams chat result as SSE events.
     *
     * <p>Current implementation chunks an already generated full answer. This keeps
     * frontend integration stable before provider-native token streaming is enabled.
     */
    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> chatStream(@RequestBody AIChatRequest request, @AuthenticationUser User user) {
        String requestedMode = resolveRequestedMode(request.getMode());
        StructuredAiResponse response = requestStructuredResponse(request, user, requestedMode);
        return streamFromAnswer(
            response.answer(),
            response.warnings() != null ? response.warnings() : List.of(),
            response.sources() != null ? response.sources() : List.of(),
            response.actions() != null ? response.actions() : List.of(),
            response.usage() != null ? response.usage() : Map.of(),
            response.modeUsed(),
            response.traceId()
        );
    }

    /**
     * Centralized structured chat invocation used by all endpoint variants.
     */
    private StructuredAiResponse requestStructuredResponse(AIChatRequest request, User user, String mode) {
        return aiOrchestratorService.chatStructured(
            user.getId(),
            request.getMessage(),
            request.getAssistantType(),
            request.getSessionId(),
            mode
        );
    }

    /**
     * Resolves requested chat mode for stream requests.
     *
     * <p>When ReAct is globally disabled and caller did not explicitly request mode,
     * force {@code llm} to avoid invoking unavailable agent pipelines.
     */
    private String resolveRequestedMode(String requestedMode) {
        if (!aiProperties.getStreamOps().isReactEnabled() && (requestedMode == null || requestedMode.isBlank())) {
            return "llm";
        }
        return requestedMode;
    }

    /**
     * Converts full answer text into SSE chunk and done events.
     */
    private Flux<ServerSentEvent<Map<String, Object>>> streamFromAnswer(
        String answer,
        List<String> warnings,
        List<String> sources,
        List<String> actions,
        Map<String, Object> usage,
        String modeUsed,
        String traceId
    ) {
        Map<String, Object> doneData = new HashMap<>();
        doneData.put("done", true);
        doneData.put("warnings", warnings);
        doneData.put("sources", sources);
        doneData.put("actions", actions);
        doneData.put("usage", usage);
        doneData.put("modeUsed", modeUsed);
        doneData.put("traceId", traceId);

        return chunkAnswer(answer)
            .map(chunk -> ServerSentEvent.<Map<String, Object>>builder()
                .event("chunk")
                .data(Map.of("content", chunk))
                .build())
            .concatWithValues(ServerSentEvent.<Map<String, Object>>builder()
                .event("done")
                .data(doneData)
                .build());
    }

    /**
     * Splits answer into fixed-size chunks to simulate streaming output.
     */
    private Flux<String> chunkAnswer(String answer) {
        if (answer == null || answer.isEmpty()) {
            return Flux.just("");
        }

        int chunkSize = 24;
        int count = (answer.length() + chunkSize - 1) / chunkSize;
        return Flux.range(0, count)
            .map(index -> {
                int start = index * chunkSize;
                int end = Math.min(start + chunkSize, answer.length());
                return answer.substring(start, end);
            });
    }
}
