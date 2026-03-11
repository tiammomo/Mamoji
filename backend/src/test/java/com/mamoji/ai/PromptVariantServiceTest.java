package com.mamoji.ai;

import com.mamoji.ai.AiProperties;
import com.mamoji.ai.prompt.PromptVariantService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test suite for PromptVariantServiceTest.
 */

class PromptVariantServiceTest {

    @Test
    void shouldPickStableVariantForSameSession() {
        PromptVariantService service = new PromptVariantService(defaultProperties());
        PromptVariantService.PromptVariant first = service.pick("finance", "u1:finance:s1");
        PromptVariantService.PromptVariant second = service.pick("finance", "u1:finance:s1");
        Assertions.assertEquals(first.variant(), second.variant());
        Assertions.assertEquals(first.bucket(), second.bucket());
    }

    @Test
    void shouldPickStockPromptForStockAssistant() {
        PromptVariantService service = new PromptVariantService(defaultProperties());
        PromptVariantService.PromptVariant stock = service.pick("stock", "u2:stock:s9");
        Assertions.assertTrue(stock.systemPrompt().toLowerCase().contains("stock"));
    }

    @Test
    void shouldRespectConfiguredWeight() {
        AiProperties properties = defaultProperties();
        properties.getPromptOps().setFinanceVariantAWeight(0);
        PromptVariantService service = new PromptVariantService(properties);

        PromptVariantService.PromptVariant variant = service.pick("finance", "u3:finance:s2");
        Assertions.assertEquals("B", variant.variant());
    }

    private AiProperties defaultProperties() {
        return new AiProperties();
    }
}



