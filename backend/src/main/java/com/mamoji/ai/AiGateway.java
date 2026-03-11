package com.mamoji.ai;

import reactor.core.publisher.Flux;

/**
 * Abstraction for AI chat providers.
 */
public interface AiGateway {

    /**
     * Sends one chat request without explicit model/assistant override.
     */
    default String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, null, null);
    }

    /**
     * Sends one chat request.
     */
    String chat(String systemPrompt, String userPrompt, String modelOverride, String assistantType);

    /**
     * Streams chat response without explicit model/assistant override.
     */
    default Flux<String> streamChat(String systemPrompt, String userPrompt) {
        return streamChat(systemPrompt, userPrompt, null, null);
    }

    /**
     * Streams chat response.
     */
    Flux<String> streamChat(String systemPrompt, String userPrompt, String modelOverride, String assistantType);
}
