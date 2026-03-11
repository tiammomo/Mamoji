package com.mamoji.ai.memory;

import java.util.List;

/**
 * Conversation memory abstraction for multi-turn chat.
 */
public interface ConversationMemoryService {

    /**
     * Appends one turn to memory.
     */
    void append(String sessionKey, String role, String content);

    /**
     * Returns recent conversation turns.
     */
    List<ConversationTurn> recent(String sessionKey, int maxTurns);

    /**
     * Clears one session memory when supported.
     */
    default void clear(String sessionKey) {
        // optional for implementations that support delete semantics.
    }
}
