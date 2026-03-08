package com.mamoji.controller;

import com.mamoji.agent.ReActAgentService;
import com.mamoji.ai.AiProperties;
import com.mamoji.ai.model.StructuredAiResponse;
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

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
@Slf4j
public class AIController {

    private static final String LEGACY_CHAT_SUNSET_DATE = "2026-04-30";
    private static final String LEGACY_CHAT_SUNSET_RFC1123 = "Thu, 30 Apr 2026 23:59:59 GMT";

    private final AIService aiService;
    private final ReActAgentService reActAgentService;
    private final AiProperties aiProperties;

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody AIChatRequest request, @AuthenticationUser User user) {
        String reply = reActAgentService.processMessage(
            user.getId(),
            request.getMessage(),
            request.getAssistantType(),
            request.getSessionId()
        );
        return ResponseEntity.ok(wrapSuccess(new AIChatResponse(reply)));
    }

    @PostMapping("/chat/v2")
    public ResponseEntity<Map<String, Object>> chatV2(@RequestBody AIChatRequest request, @AuthenticationUser User user) {
        StructuredAiResponse response = reActAgentService.processMessageStructured(
            user.getId(),
            request.getMessage(),
            request.getAssistantType(),
            request.getSessionId()
        );
        return ResponseEntity.ok(wrapSuccess(response));
    }

    @PostMapping("/chat/legacy")
    @Deprecated(forRemoval = true, since = "2026-03-08")
    public ResponseEntity<Map<String, Object>> chatLegacy(@RequestBody AIChatRequest request, @AuthenticationUser User user) {
        log.warn("Deprecated endpoint called userId={} endpoint=/api/v1/ai/chat/legacy", user.getId());
        AIChatResponse response = aiService.chat(user.getId(), request.getMessage(), request.getAssistantType());
        Map<String, Object> payload = wrapSuccess(response);
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

    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> chatStream(@RequestBody AIChatRequest request, @AuthenticationUser User user) {
        if (!aiProperties.getStreamOps().isReactEnabled()) {
            AIChatResponse response = aiService.chat(user.getId(), request.getMessage(), request.getAssistantType());
            return streamFromAnswer(response.getReply(), List.of(), List.of(), List.of());
        }

        StructuredAiResponse response = reActAgentService.processMessageStructured(
            user.getId(),
            request.getMessage(),
            request.getAssistantType(),
            request.getSessionId()
        );

        return streamFromAnswer(
            response.answer(),
            response.warnings() != null ? response.warnings() : List.of(),
            response.sources() != null ? response.sources() : List.of(),
            response.actions() != null ? response.actions() : List.of(),
            response.usage() != null ? response.usage() : Map.of()
        );
    }

    private Flux<ServerSentEvent<Map<String, Object>>> streamFromAnswer(
        String answer,
        List<String> warnings,
        List<String> sources,
        List<String> actions
    ) {
        return streamFromAnswer(answer, warnings, sources, actions, defaultUsage(answer));
    }

    private Flux<ServerSentEvent<Map<String, Object>>> streamFromAnswer(
        String answer,
        List<String> warnings,
        List<String> sources,
        List<String> actions,
        Map<String, Object> usage
    ) {
        Map<String, Object> doneData = new HashMap<>();
        doneData.put("done", true);
        doneData.put("warnings", warnings != null ? warnings : List.of());
        doneData.put("sources", sources != null ? sources : List.of());
        doneData.put("actions", actions != null ? actions : List.of());
        doneData.put("usage", usage != null ? usage : Map.of());

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

    private Map<String, Object> defaultUsage(String answer) {
        String content = answer != null ? answer : "";
        return Map.of(
            "outputChars", content.length(),
            "estimatedTokens", Math.max(1, content.length() / 4)
        );
    }

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

    private Map<String, Object> wrapSuccess(Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", data);
        return result;
    }
}
