package com.mamoji.ai;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiModelRouterTest {

    @Test
    void shouldSelectAssistantSpecificModelAndReasons() {
        AiProperties properties = new AiProperties();
        properties.setModel("default-model");
        properties.getRoutingOps().setEnabled(true);
        properties.getRoutingOps().setFinanceModel("finance-model");
        properties.getRoutingOps().setStockModel("stock-model");
        AiModelRouter router = new AiModelRouter(properties);

        AiModelRouter.RoutingDecision financeDecision = router.pickPrimaryModelDecision("finance", "budget advice");
        Assertions.assertEquals("finance-model", financeDecision.model());
        Assertions.assertEquals("assistant_type_finance", financeDecision.reason());

        AiModelRouter.RoutingDecision stockDecision = router.pickPrimaryModelDecision("stock", "600519");
        Assertions.assertEquals("stock-model", stockDecision.model());
        Assertions.assertEquals("assistant_type_stock", stockDecision.reason());
    }

    @Test
    void shouldSelectHighComplexityModelByQuestionLength() {
        AiProperties properties = new AiProperties();
        properties.setModel("default-model");
        properties.getRoutingOps().setEnabled(true);
        properties.getRoutingOps().setHighComplexityModel("complex-model");
        properties.getRoutingOps().setHighComplexityQuestionChars(50);
        AiModelRouter router = new AiModelRouter(properties);

        String longQuestion = "a".repeat(120);
        AiModelRouter.RoutingDecision decision = router.pickPrimaryModelDecision("finance", longQuestion);
        Assertions.assertEquals("complex-model", decision.model());
        Assertions.assertEquals("high_complexity", decision.reason());
    }

    @Test
    void shouldExposeReasonWhenRoutingDisabled() {
        AiProperties properties = new AiProperties();
        properties.setModel("default-model");
        properties.getRoutingOps().setEnabled(false);
        AiModelRouter router = new AiModelRouter(properties);

        AiModelRouter.RoutingDecision decision = router.pickPrimaryModelDecision("finance", "hello");
        Assertions.assertEquals("default-model", decision.model());
        Assertions.assertEquals("routing_disabled", decision.reason());
    }
}
