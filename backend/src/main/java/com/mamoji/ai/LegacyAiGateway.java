package com.mamoji.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Legacy gateway adapter based on existing {@link AiClientService}.
 */
@Service
@RequiredArgsConstructor
public class LegacyAiGateway implements AiGateway {

    private final AiClientService aiClientService;

    /**
     * Executes synchronous chat through legacy client.
     */
    @Override
    public String chat(String systemPrompt, String userPrompt, String modelOverride, String assistantType) {
        return aiClientService.chat(systemPrompt, userPrompt, modelOverride, assistantType);
    }

    /**
     * Executes pseudo-stream chat through legacy client.
     */
    @Override
    public Flux<String> streamChat(String systemPrompt, String userPrompt, String modelOverride, String assistantType) {
        return aiClientService.streamChat(systemPrompt, userPrompt);
    }
}
