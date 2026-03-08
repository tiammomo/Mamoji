package com.mamoji.ai.memory;

import java.util.List;

public interface ConversationMemoryService {

    void append(String sessionKey, String role, String content);

    List<ConversationTurn> recent(String sessionKey, int maxTurns);

    default void clear(String sessionKey) {
        // optional for implementations that support delete semantics.
    }
}
