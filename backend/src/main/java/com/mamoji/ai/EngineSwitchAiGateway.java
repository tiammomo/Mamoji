package com.mamoji.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Locale;

/**
 * Gateway switch that delegates to configured engine implementation.
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class EngineSwitchAiGateway implements AiGateway {

    private final AiProperties aiProperties;
    private final LegacyAiGateway legacyAiGateway;
    private final ObjectProvider<SpringAiGateway> springAiGatewayProvider;

    /**
     * Delegates chat call to selected gateway.
     */
    @Override
    public String chat(String systemPrompt, String userPrompt, String modelOverride, String assistantType) {
        return selectedGateway().chat(systemPrompt, userPrompt, modelOverride, assistantType);
    }

    /**
     * Delegates stream call to selected gateway.
     */
    @Override
    public Flux<String> streamChat(String systemPrompt, String userPrompt, String modelOverride, String assistantType) {
        return selectedGateway().streamChat(systemPrompt, userPrompt, modelOverride, assistantType);
    }

    /**
     * Resolves active gateway implementation by {@code ai.engine}.
     */
    private AiGateway selectedGateway() {
        String engine = normalizeEngine(aiProperties.getEngine());
        if ("legacy".equals(engine)) {
            return legacyAiGateway;
        }
        if ("spring-ai".equals(engine)) {
            SpringAiGateway springAiGateway = springAiGatewayProvider.getIfAvailable();
            if (springAiGateway != null) {
                return springAiGateway;
            }
            log.warn("ai.engine=spring-ai but no SpringAiGateway bean found, fallback to legacy");
            return legacyAiGateway;
        }
        log.warn("Unsupported ai.engine={}, fallback to legacy", aiProperties.getEngine());
        return legacyAiGateway;
    }

    /**
     * Normalizes engine string for safe comparison.
     */
    private String normalizeEngine(String engine) {
        if (engine == null || engine.isBlank()) {
            return "legacy";
        }
        return engine.trim().toLowerCase(Locale.ROOT);
    }
}
