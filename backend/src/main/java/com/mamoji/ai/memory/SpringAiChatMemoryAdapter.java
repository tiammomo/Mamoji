package com.mamoji.ai.memory;

import com.mamoji.ai.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnClass(ChatMemory.class)
public class SpringAiChatMemoryAdapter implements ChatMemory {

    private final ConversationMemoryService conversationMemoryService;
    private final AiProperties aiProperties;

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (conversationId == null || conversationId.isBlank() || messages == null || messages.isEmpty()) {
            return;
        }
        for (Message message : messages) {
            if (message == null || message.getText() == null || message.getText().isBlank()) {
                continue;
            }
            conversationMemoryService.append(conversationId, toRole(message.getMessageType()), message.getText());
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        int maxTurns = Math.max(2, aiProperties.getMemoryOps().getMaxStoredTurns());
        List<ConversationTurn> turns = conversationMemoryService.recent(conversationId, maxTurns);
        if (turns.isEmpty()) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>(turns.size());
        for (ConversationTurn turn : turns) {
            Message message = toMessage(turn);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    @Override
    public void clear(String conversationId) {
        conversationMemoryService.clear(conversationId);
    }

    public MessageChatMemoryAdvisor toAdvisor() {
        return MessageChatMemoryAdvisor.builder(this).build();
    }

    private String toRole(MessageType messageType) {
        if (messageType == null) {
            return "user";
        }
        return switch (messageType) {
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            case TOOL -> "tool";
            default -> "user";
        };
    }

    private Message toMessage(ConversationTurn turn) {
        if (turn == null || turn.content() == null || turn.content().isBlank()) {
            return null;
        }
        String role = turn.role() == null ? "" : turn.role().toLowerCase();
        if (role.startsWith("assistant")) {
            return new AssistantMessage(turn.content());
        }
        if (role.startsWith("system")) {
            return new SystemMessage(turn.content());
        }
        return new UserMessage(turn.content());
    }
}
