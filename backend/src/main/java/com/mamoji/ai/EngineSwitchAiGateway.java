package com.mamoji.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Locale;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class EngineSwitchAiGateway implements AiGateway {

    private final AiProperties aiProperties;
    private final LegacyAiGateway legacyAiGateway;
    private final ObjectProvider<SpringAiGateway> springAiGatewayProvider;

    @Override
    public String chat(String systemPrompt, String userPrompt, String modelOverride, String assistantType) {
        return selectedGateway().chat(systemPrompt, userPrompt, modelOverride, assistantType);
    }

    @Override
    public Flux<String> streamChat(String systemPrompt, String userPrompt, String modelOverride, String assistantType) {
        return selectedGateway().streamChat(systemPrompt, userPrompt, modelOverride, assistantType);
    }

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

    private String normalizeEngine(String engine) {
        if (engine == null || engine.isBlank()) {
            return "legacy";
        }
        return engine.trim().toLowerCase(Locale.ROOT);
    }
}
