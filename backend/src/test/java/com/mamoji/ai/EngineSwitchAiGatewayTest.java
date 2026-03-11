package com.mamoji.ai;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Test suite for EngineSwitchAiGatewayTest.
 */

class EngineSwitchAiGatewayTest {

    @Test
    void shouldUseLegacyGatewayWhenEngineIsLegacy() {
        AiProperties properties = new AiProperties();
        properties.setEngine("legacy");

        LegacyAiGateway legacyAiGateway = Mockito.mock(LegacyAiGateway.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SpringAiGateway> springProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(legacyAiGateway.chat("sys", "user", null, "finance")).thenReturn("legacy-answer");

        EngineSwitchAiGateway gateway = new EngineSwitchAiGateway(properties, legacyAiGateway, springProvider);
        String answer = gateway.chat("sys", "user", null, "finance");

        Assertions.assertEquals("legacy-answer", answer);
    }

    @Test
    void shouldFallbackToLegacyGatewayWhenEngineUnsupported() {
        AiProperties properties = new AiProperties();
        properties.setEngine("spring-ai");

        LegacyAiGateway legacyAiGateway = Mockito.mock(LegacyAiGateway.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SpringAiGateway> springProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(legacyAiGateway.streamChat("sys", "user", null, null))
            .thenReturn(Flux.just("a", "b"));
        Mockito.when(springProvider.getIfAvailable()).thenReturn(null);

        EngineSwitchAiGateway gateway = new EngineSwitchAiGateway(properties, legacyAiGateway, springProvider);
        List<String> chunks = gateway.streamChat("sys", "user", null, null).collectList().block();

        Assertions.assertEquals(List.of("a", "b"), chunks);
    }

    @Test
    void shouldUseSpringGatewayWhenConfiguredAndAvailable() {
        AiProperties properties = new AiProperties();
        properties.setEngine("spring-ai");

        LegacyAiGateway legacyAiGateway = Mockito.mock(LegacyAiGateway.class);
        SpringAiGateway springAiGateway = Mockito.mock(SpringAiGateway.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SpringAiGateway> springProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(springProvider.getIfAvailable()).thenReturn(springAiGateway);
        Mockito.when(springAiGateway.chat("sys", "user", null, "finance")).thenReturn("spring-answer");

        EngineSwitchAiGateway gateway = new EngineSwitchAiGateway(properties, legacyAiGateway, springProvider);
        String answer = gateway.chat("sys", "user", null, "finance");

        Assertions.assertEquals("spring-answer", answer);
    }
}



