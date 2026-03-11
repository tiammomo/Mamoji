package com.mamoji.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.ai.AiGateway;
import com.mamoji.ai.AiModelRouter;
import com.mamoji.ai.intent.FinanceIntentClassifier;
import com.mamoji.ai.memory.ConversationMemoryService;
import com.mamoji.ai.metrics.AiMetricsService;
import com.mamoji.ai.model.StructuredAiResponse;
import com.mamoji.ai.prompt.PromptVariantService;
import com.mamoji.ai.quality.AiQualityGateService;
import com.mamoji.ai.rag.KnowledgeRetriever;
import com.mamoji.ai.tool.AiToolResult;
import com.mamoji.ai.tool.AiToolRouter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

/**
 * Test suite for ReActAgentServiceStructuredOutputTest.
 */

class ReActAgentServiceStructuredOutputTest {

    @Test
    void shouldRetryOnceWhenStructuredOutputParsingFails() {
        AiGateway aiGateway = Mockito.mock(AiGateway.class);
        AiToolRouter aiToolRouter = Mockito.mock(AiToolRouter.class);
        ConversationMemoryService memoryService = Mockito.mock(ConversationMemoryService.class);
        KnowledgeRetriever knowledgeRetriever = Mockito.mock(KnowledgeRetriever.class);
        PromptVariantService promptVariantService = Mockito.mock(PromptVariantService.class);
        AiQualityGateService qualityGateService = Mockito.mock(AiQualityGateService.class);
        AiMetricsService aiMetricsService = Mockito.mock(AiMetricsService.class);
        AiModelRouter aiModelRouter = Mockito.mock(AiModelRouter.class);
        FinanceIntentClassifier financeIntentClassifier = Mockito.mock(FinanceIntentClassifier.class);

        Mockito.when(knowledgeRetriever.retrieve(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(List.of());
        Mockito.when(memoryService.recent(Mockito.anyString(), Mockito.anyInt())).thenReturn(List.of());
        Mockito.when(promptVariantService.pick(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(new PromptVariantService.PromptVariant("A", "system-prompt", "exp-v1", 11));
        Mockito.when(qualityGateService.validate(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
            .thenReturn(List.of());
        Mockito.when(aiModelRouter.pickPrimaryModelDecision(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(new AiModelRouter.RoutingDecision("route-model", "default"));
        Mockito.when(financeIntentClassifier.classify(Mockito.anyString()))
            .thenReturn(new FinanceIntentClassifier.FinanceIntent(FinanceIntentClassifier.FinanceIntentType.UNKNOWN, 0.0D, null));
        Mockito.when(aiGateway.chat(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any()))
            .thenReturn("plain text answer")
            .thenReturn("{\"answer\":\"fixed json answer\",\"warnings\":[],\"sources\":[],\"actions\":[]}");

        ReActAgentService service = new ReActAgentService(
            aiGateway,
            aiToolRouter,
            memoryService,
            knowledgeRetriever,
            promptVariantService,
            qualityGateService,
            aiMetricsService,
            aiModelRouter,
            financeIntentClassifier,
            new StructuredAnswerParser(new ObjectMapper()),
            new ObjectMapper()
        );

        StructuredAiResponse response = service.processMessageStructured(1L, "hello", "finance", "s1");

        Assertions.assertEquals("fixed json answer", response.answer());
        Assertions.assertTrue(response.warnings().contains("schema_repair_retry"));
        Mockito.verify(aiGateway, Mockito.times(2)).chat(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any());
    }

    @Test
    void shouldSkipQualityGateWhenSchemaParsingStillFailsAfterRepair() {
        AiGateway aiGateway = Mockito.mock(AiGateway.class);
        AiToolRouter aiToolRouter = Mockito.mock(AiToolRouter.class);
        ConversationMemoryService memoryService = Mockito.mock(ConversationMemoryService.class);
        KnowledgeRetriever knowledgeRetriever = Mockito.mock(KnowledgeRetriever.class);
        PromptVariantService promptVariantService = Mockito.mock(PromptVariantService.class);
        AiQualityGateService qualityGateService = Mockito.mock(AiQualityGateService.class);
        AiMetricsService aiMetricsService = Mockito.mock(AiMetricsService.class);
        AiModelRouter aiModelRouter = Mockito.mock(AiModelRouter.class);
        FinanceIntentClassifier financeIntentClassifier = Mockito.mock(FinanceIntentClassifier.class);

        Mockito.when(knowledgeRetriever.retrieve(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(List.of());
        Mockito.when(memoryService.recent(Mockito.anyString(), Mockito.anyInt())).thenReturn(List.of());
        Mockito.when(promptVariantService.pick(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(new PromptVariantService.PromptVariant("A", "system-prompt", "exp-v1", 11));
        Mockito.when(aiModelRouter.pickPrimaryModelDecision(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(new AiModelRouter.RoutingDecision("route-model", "default"));
        Mockito.when(financeIntentClassifier.classify(Mockito.anyString()))
            .thenReturn(new FinanceIntentClassifier.FinanceIntent(FinanceIntentClassifier.FinanceIntentType.UNKNOWN, 0.0D, null));
        Mockito.when(aiGateway.chat(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any()))
            .thenReturn("non-json answer 1")
            .thenReturn("non-json answer 2");

        ReActAgentService service = new ReActAgentService(
            aiGateway,
            aiToolRouter,
            memoryService,
            knowledgeRetriever,
            promptVariantService,
            qualityGateService,
            aiMetricsService,
            aiModelRouter,
            financeIntentClassifier,
            new StructuredAnswerParser(new ObjectMapper()),
            new ObjectMapper()
        );

        StructuredAiResponse response = service.processMessageStructured(1L, "hello", "finance", "s1");

        Assertions.assertTrue(response.warnings().contains("schema_parse_failed"));
        Mockito.verify(qualityGateService, Mockito.never()).validate(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void shouldFallbackToBudgetToolSummaryWhenGatewayReturnsInvalidFormat() {
        AiGateway aiGateway = Mockito.mock(AiGateway.class);
        AiToolRouter aiToolRouter = Mockito.mock(AiToolRouter.class);
        ConversationMemoryService memoryService = Mockito.mock(ConversationMemoryService.class);
        KnowledgeRetriever knowledgeRetriever = Mockito.mock(KnowledgeRetriever.class);
        PromptVariantService promptVariantService = Mockito.mock(PromptVariantService.class);
        AiQualityGateService qualityGateService = Mockito.mock(AiQualityGateService.class);
        AiMetricsService aiMetricsService = Mockito.mock(AiMetricsService.class);
        AiModelRouter aiModelRouter = Mockito.mock(AiModelRouter.class);
        FinanceIntentClassifier financeIntentClassifier = Mockito.mock(FinanceIntentClassifier.class);

        Mockito.when(knowledgeRetriever.retrieve(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(List.of());
        Mockito.when(memoryService.recent(Mockito.anyString(), Mockito.anyInt())).thenReturn(List.of());
        Mockito.when(promptVariantService.pick(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(new PromptVariantService.PromptVariant("A", "system-prompt", "exp-v1", 11));
        Mockito.when(aiModelRouter.pickPrimaryModelDecision(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(new AiModelRouter.RoutingDecision("route-model", "default"));
        Mockito.when(financeIntentClassifier.classify(Mockito.anyString()))
            .thenReturn(new FinanceIntentClassifier.FinanceIntent(FinanceIntentClassifier.FinanceIntentType.BUDGET, 1.0D, null));
        Mockito.when(aiToolRouter.route(Mockito.anyLong(), Mockito.eq("finance"), Mockito.anyMap()))
            .thenReturn(AiToolResult.ok(
                "finance.query_budget",
                "{\"name\":\"March Budget\",\"budgetAmount\":3000,\"spent\":1800,\"remaining\":1200,\"usageRate\":60.0,\"status\":\"normal\",\"period\":\"2026-03-01 to 2026-03-31\"}"
            ));
        Mockito.when(aiGateway.chat(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any()))
            .thenReturn("AI service returned invalid response format")
            .thenReturn("AI service returned invalid response format");

        ReActAgentService service = new ReActAgentService(
            aiGateway,
            aiToolRouter,
            memoryService,
            knowledgeRetriever,
            promptVariantService,
            qualityGateService,
            aiMetricsService,
            aiModelRouter,
            financeIntentClassifier,
            new StructuredAnswerParser(new ObjectMapper()),
            new ObjectMapper()
        );

        StructuredAiResponse response = service.processMessageStructured(1L, "How is my budget usage?", "finance", "s1");

        Assertions.assertFalse(response.warnings().contains("schema_parse_failed"));
        Assertions.assertNotNull(response.answer());
        Assertions.assertFalse(response.answer().isBlank());
        Assertions.assertTrue(response.sources().contains("tool:finance.query_budget"));
    }
}



