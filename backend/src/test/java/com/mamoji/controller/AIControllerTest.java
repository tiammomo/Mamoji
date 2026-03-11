package com.mamoji.controller;

import com.mamoji.ai.AiOrchestratorService;
import com.mamoji.ai.AiProperties;
import com.mamoji.ai.model.StructuredAiResponse;
import com.mamoji.dto.AIChatRequest;
import com.mamoji.dto.AIChatResponse;
import com.mamoji.entity.User;
import com.mamoji.service.AIService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;

import java.util.List;
import java.util.Map;

/**
 * Test suite for AIControllerTest.
 */

class AIControllerTest {

    @Test
    void shouldStreamChunksAndDoneMetadataFromOrchestrator() {
        AIService aiService = Mockito.mock(AIService.class);
        AiOrchestratorService aiOrchestratorService = Mockito.mock(AiOrchestratorService.class);
        AiProperties aiProperties = new AiProperties();
        aiProperties.getStreamOps().setReactEnabled(true);
        AIController controller = new AIController(aiService, aiOrchestratorService, aiProperties);

        String answer = "12345678901234567890123456789012345678901234567890";
        StructuredAiResponse structured = new StructuredAiResponse(
            answer,
            List.of("kb:budget"),
            List.of("tool:query_budget"),
            List.of("schema_repair_retry"),
            Map.of("estimatedTokens", 42),
            "agent",
            "trace001"
        );
        Mockito.when(aiOrchestratorService.chatStructured(7L, "budget suggestion", "finance", "s1", "agent"))
            .thenReturn(structured);

        AIChatRequest request = new AIChatRequest();
        request.setMessage("budget suggestion");
        request.setAssistantType("finance");
        request.setSessionId("s1");
        request.setMode("agent");
        User user = User.builder().id(7L).build();

        List<ServerSentEvent<Map<String, Object>>> events = controller.chatStream(request, user)
            .collectList()
            .block();

        Assertions.assertNotNull(events);
        Assertions.assertEquals(4, events.size());
        Assertions.assertEquals("chunk", events.get(0).event());
        Assertions.assertEquals("123456789012345678901234", events.get(0).data().get("content"));
        Assertions.assertEquals("chunk", events.get(1).event());
        Assertions.assertEquals("567890123456789012345678", events.get(1).data().get("content"));
        Assertions.assertEquals("chunk", events.get(2).event());
        Assertions.assertEquals("90", events.get(2).data().get("content"));

        ServerSentEvent<Map<String, Object>> done = events.get(3);
        Assertions.assertEquals("done", done.event());
        Assertions.assertEquals(Boolean.TRUE, done.data().get("done"));
        Assertions.assertEquals(List.of("schema_repair_retry"), done.data().get("warnings"));
        Assertions.assertEquals(List.of("kb:budget"), done.data().get("sources"));
        Assertions.assertEquals(List.of("tool:query_budget"), done.data().get("actions"));
        Assertions.assertEquals(Map.of("estimatedTokens", 42), done.data().get("usage"));
        Assertions.assertEquals("agent", done.data().get("modeUsed"));
        Assertions.assertEquals("trace001", done.data().get("traceId"));

        Mockito.verify(aiOrchestratorService).chatStructured(7L, "budget suggestion", "finance", "s1", "agent");
        Mockito.verifyNoInteractions(aiService);
    }

    @Test
    void shouldExposeLegacyDeprecationHeadersAndMigrationInfo() {
        AIService aiService = Mockito.mock(AIService.class);
        AiOrchestratorService aiOrchestratorService = Mockito.mock(AiOrchestratorService.class);
        AiProperties aiProperties = new AiProperties();
        AIController controller = new AIController(aiService, aiOrchestratorService, aiProperties);

        Mockito.when(aiService.chat(7L, "hello", "finance")).thenReturn(new AIChatResponse("legacy-reply"));

        AIChatRequest request = new AIChatRequest();
        request.setMessage("hello");
        request.setAssistantType("finance");
        User user = User.builder().id(7L).build();

        ResponseEntity<Map<String, Object>> response = controller.chatLegacy(request, user);

        Assertions.assertEquals("true", response.getHeaders().getFirst("Deprecation"));
        Assertions.assertEquals("Thu, 30 Apr 2026 23:59:59 GMT", response.getHeaders().getFirst("Sunset"));
        Assertions.assertEquals("</api/v1/ai/chat>; rel=\"successor-version\"", response.getHeaders().getFirst("Link"));

        Map<String, Object> body = response.getBody();
        Assertions.assertNotNull(body);
        Assertions.assertEquals(0, body.get("code"));

        @SuppressWarnings("unchecked")
        Map<String, Object> deprecation = (Map<String, Object>) body.get("deprecation");
        Assertions.assertNotNull(deprecation);
        Assertions.assertEquals("/api/v1/ai/chat/legacy", deprecation.get("endpoint"));
        Assertions.assertEquals("/api/v1/ai/chat", deprecation.get("successor"));
        Assertions.assertEquals("2026-04-30", deprecation.get("sunsetDate"));
    }

    @Test
    void shouldForceLlmModeWhenReactStreamDisabled() {
        AIService aiService = Mockito.mock(AIService.class);
        AiOrchestratorService aiOrchestratorService = Mockito.mock(AiOrchestratorService.class);
        AiProperties aiProperties = new AiProperties();
        aiProperties.getStreamOps().setReactEnabled(false);
        AIController controller = new AIController(aiService, aiOrchestratorService, aiProperties);

        Mockito.when(aiOrchestratorService.chatStructured(7L, "hello", "finance", null, "llm"))
            .thenReturn(new StructuredAiResponse(
                "legacy answer body",
                List.of(),
                List.of(),
                List.of(),
                Map.of("estimatedTokens", 5),
                "llm",
                "trace-llm"
            ));

        AIChatRequest request = new AIChatRequest();
        request.setMessage("hello");
        request.setAssistantType("finance");
        User user = User.builder().id(7L).build();

        List<ServerSentEvent<Map<String, Object>>> events = controller.chatStream(request, user).collectList().block();

        Assertions.assertNotNull(events);
        Assertions.assertFalse(events.isEmpty());
        Assertions.assertEquals("chunk", events.get(0).event());
        Assertions.assertEquals("legacy answer body", events.get(0).data().get("content"));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
        Mockito.verify(aiOrchestratorService).chatStructured(7L, "hello", "finance", null, "llm");
        Mockito.verifyNoInteractions(aiService);
    }
}




