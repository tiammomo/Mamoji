package com.mamoji.ai;

import com.mamoji.ai.metrics.AiMetricsService;
import com.mamoji.ai.tool.SpringAiToolCallingBridge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Service;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnClass(ChatClient.class)
@ConditionalOnBean(ChatModel.class)
public class SpringAiGateway implements AiGateway {

    private static final String FALLBACK_ERROR_MESSAGE = "Sorry, AI service is temporarily unavailable. Please try again later.";

    private final ChatModel chatModel;
    private final AiMetricsService metricsService;
    private final ObjectProvider<ChatMemory> chatMemoryProvider;
    private final SpringAiToolCallingBridge springAiToolCallingBridge;

    @Override
    public String chat(String systemPrompt, String userPrompt, String modelOverride, String assistantType) {
        long start = System.currentTimeMillis();
        try {
            ChatMemory chatMemory = chatMemoryProvider.getIfAvailable();
            String conversationId = conversationId(assistantType);
            SpringAiToolCallingBridge.ToolCallingContext toolContext = springAiToolCallingBridge.invoke(assistantType, userPrompt);
            String prompt = buildPrompt(systemPrompt, userPrompt, modelOverride, chatMemory, conversationId, toolContext);
            String result = ChatClient.create(chatModel)
                .prompt(prompt)
                .call()
                .content();
            saveConversation(chatMemory, conversationId, userPrompt, result);
            if (!toolContext.warnings().isEmpty()) {
                log.warn("Spring tool-calling warnings assistantType={} warnings={}", assistantType, toolContext.warnings());
            }
            long elapsed = System.currentTimeMillis() - start;
            metricsService.recordRequest("spring-ai", true, elapsed, estimateTokens(prompt, result));
            metricsService.recordModelRoute(assistantType, modelOverride != null ? modelOverride : "spring-ai-default");
            return result != null ? result : "AI service returned empty response";
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Spring AI request failed elapsedMs={} error={}", elapsed, ex.getMessage(), ex);
            metricsService.recordRequest("spring-ai", false, elapsed, estimateTokens(systemPrompt, userPrompt));
            return FALLBACK_ERROR_MESSAGE;
        }
    }

    @Override
    public Flux<String> streamChat(String systemPrompt, String userPrompt, String modelOverride, String assistantType) {
        return chunkText(chat(systemPrompt, userPrompt, modelOverride, assistantType));
    }

    private String buildPrompt(
        String systemPrompt,
        String userPrompt,
        String modelOverride,
        ChatMemory chatMemory,
        String conversationId,
        SpringAiToolCallingBridge.ToolCallingContext toolContext
    ) {
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System:\n").append(systemPrompt.trim()).append("\n\n");
        }
        if (modelOverride != null && !modelOverride.isBlank()) {
            prompt.append("Preferred model: ").append(modelOverride.trim()).append("\n");
        }
        appendConversationHistory(prompt, chatMemory, conversationId);
        if (toolContext != null && toolContext.promptAddon() != null && !toolContext.promptAddon().isBlank()) {
            prompt.append(toolContext.promptAddon());
        }
        prompt.append("User:\n").append(userPrompt != null ? userPrompt : "");
        return prompt.toString();
    }

    private void appendConversationHistory(StringBuilder prompt, ChatMemory chatMemory, String conversationId) {
        if (chatMemory == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        List<Message> history = chatMemory.get(conversationId);
        if (history == null || history.isEmpty()) {
            return;
        }
        prompt.append("Conversation history:\n");
        int from = Math.max(0, history.size() - 8);
        for (int i = from; i < history.size(); i++) {
            Message message = history.get(i);
            if (message == null || message.getText() == null || message.getText().isBlank()) {
                continue;
            }
            prompt.append("- ")
                .append(message.getMessageType() != null ? message.getMessageType().name().toLowerCase() : "unknown")
                .append(": ")
                .append(message.getText())
                .append("\n");
        }
        prompt.append("\n");
    }

    private void saveConversation(ChatMemory chatMemory, String conversationId, String userPrompt, String answer) {
        if (chatMemory == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        if (userPrompt != null && !userPrompt.isBlank()) {
            chatMemory.add(conversationId, new UserMessage(userPrompt));
        }
        if (answer != null && !answer.isBlank()) {
            chatMemory.add(conversationId, new AssistantMessage(answer));
        }
    }

    private String conversationId(String assistantType) {
        if (assistantType == null || assistantType.isBlank()) {
            return "spring-ai:default";
        }
        return "spring-ai:" + assistantType.trim().toLowerCase();
    }

    private Flux<String> chunkText(String fullText) {
        if (fullText == null || fullText.isEmpty()) {
            return Flux.just("");
        }
        int chunkSize = 24;
        int count = (fullText.length() + chunkSize - 1) / chunkSize;
        return Flux.range(0, count)
            .map(i -> {
                int start = i * chunkSize;
                int end = Math.min(start + chunkSize, fullText.length());
                return fullText.substring(start, end);
            });
    }

    private int estimateTokens(String input, String output) {
        int chars = 0;
        chars += input != null ? input.length() : 0;
        chars += output != null ? output.length() : 0;
        return Math.max(1, chars / 4);
    }
}
