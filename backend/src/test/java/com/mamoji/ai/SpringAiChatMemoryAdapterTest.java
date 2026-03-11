package com.mamoji.ai;

import com.mamoji.ai.memory.InMemoryConversationMemoryService;
import com.mamoji.ai.memory.SpringAiChatMemoryAdapter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * Test suite for SpringAiChatMemoryAdapterTest.
 */

class SpringAiChatMemoryAdapterTest {

    @Test
    void shouldBridgeConversationMemoryToSpringAiChatMemory() {
        AiProperties properties = new AiProperties();
        InMemoryConversationMemoryService memoryService = new InMemoryConversationMemoryService(properties);
        SpringAiChatMemoryAdapter adapter = new SpringAiChatMemoryAdapter(memoryService, properties);

        adapter.add("conv-1", List.of(new UserMessage("hello"), new AssistantMessage("hi")));
        List<Message> messages = adapter.get("conv-1");

        Assertions.assertEquals(2, messages.size());
        Assertions.assertEquals(MessageType.USER, messages.get(0).getMessageType());
        Assertions.assertEquals("hello", messages.get(0).getText());
        Assertions.assertEquals(MessageType.ASSISTANT, messages.get(1).getMessageType());
        Assertions.assertEquals("hi", messages.get(1).getText());

        adapter.clear("conv-1");
        Assertions.assertTrue(adapter.get("conv-1").isEmpty());
    }
}



