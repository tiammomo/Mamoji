package com.mamoji.ai.memory;

import java.time.Instant;

/**
 * Immutable conversation turn record.
 */
public record ConversationTurn(
    String role,
    String content,
    Instant timestamp
) {
}

