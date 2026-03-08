package com.mamoji.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class LegacyAiGateway implements AiGateway {

    private final AiClientService aiClientService;

    @Override
    public String chat(String systemPrompt, String userPrompt, String modelOverride, String assistantType) {
        return aiClientService.chat(systemPrompt, userPrompt, modelOverride, assistantType);
    }

    @Override
    public Flux<String> streamChat(String systemPrompt, String userPrompt, String modelOverride, String assistantType) {
        return aiClientService.streamChat(systemPrompt, userPrompt);
    }
}
