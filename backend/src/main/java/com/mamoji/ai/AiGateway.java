package com.mamoji.ai;

import reactor.core.publisher.Flux;

public interface AiGateway {

    default String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, null, null);
    }

    String chat(String systemPrompt, String userPrompt, String modelOverride, String assistantType);

    default Flux<String> streamChat(String systemPrompt, String userPrompt) {
        return streamChat(systemPrompt, userPrompt, null, null);
    }

    Flux<String> streamChat(String systemPrompt, String userPrompt, String modelOverride, String assistantType);
}
