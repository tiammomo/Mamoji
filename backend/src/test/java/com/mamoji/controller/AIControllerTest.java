package com.mamoji.controller;

import com.mamoji.agent.ReActAgentService;
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

class AIControllerTest {

    @Test
    void shouldStreamChunksAndDoneMetadataFromReactPipeline() {
        AIService aiService = Mockito.mock(AIService.class);
        ReActAgentService reActAgentService = Mockito.mock(ReActAgentService.class);
        AiProperties aiProperties = new AiProperties();
        aiProperties.getStreamOps().setReactEnabled(true);
        AIController controller = new AIController(aiService, reActAgentService, aiProperties);

        String answer = "12345678901234567890123456789012345678901234567890";
        StructuredAiResponse structured = new StructuredAiResponse(
            answer,
            List.of("kb:budget"),
            List.of("tool:query_budget"),
            List.of("schema_repair_retry"),
            Map.of("estimatedTokens", 42)
        );
        Mockito.when(reActAgentService.processMessageStructured(7L, "budget suggestion", "finance", "s1"))
            .thenReturn(structured);

        AIChatRequest request = new AIChatRequest();
        request.setMessage("budget suggestion");
        request.setAssistantType("finance");
        request.setSessionId("s1");
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

        Mockito.verify(reActAgentService).processMessageStructured(7L, "budget suggestion", "finance", "s1");
        Mockito.verifyNoInteractions(aiService);
    }

    @Test
    void shouldExposeLegacyDeprecationHeadersAndMigrationInfo() {
        AIService aiService = Mockito.mock(AIService.class);
        ReActAgentService reActAgentService = Mockito.mock(ReActAgentService.class);
        AiProperties aiProperties = new AiProperties();
        AIController controller = new AIController(aiService, reActAgentService, aiProperties);

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
    void shouldFallbackToLegacyStreamWhenReactStreamDisabled() {
        AIService aiService = Mockito.mock(AIService.class);
        ReActAgentService reActAgentService = Mockito.mock(ReActAgentService.class);
        AiProperties aiProperties = new AiProperties();
        aiProperties.getStreamOps().setReactEnabled(false);
        AIController controller = new AIController(aiService, reActAgentService, aiProperties);

        Mockito.when(aiService.chat(7L, "hello", "finance")).thenReturn(new AIChatResponse("legacy answer body"));

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
        Mockito.verify(aiService).chat(7L, "hello", "finance");
        Mockito.verifyNoInteractions(reActAgentService);
    }
}
