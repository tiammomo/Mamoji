package com.mamoji.ai;

import java.util.Locale;

/**
 * Supported chat execution modes.
 */
public enum AiChatMode {
    LLM("llm"),
    AGENT("agent"),
    AUTO("auto");

    private final String value;

    AiChatMode(String value) {
        this.value = value;
    }

    /**
     * Returns serialized value used by API payloads.
     */
    public String value() {
        return value;
    }

    /**
     * Parses mode text and falls back to {@link #AUTO} when input is unknown.
     */
    public static AiChatMode from(String mode) {
        if (mode == null || mode.isBlank()) {
            return AUTO;
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "llm" -> LLM;
            case "agent" -> AGENT;
            case "auto" -> AUTO;
            default -> AUTO;
        };
    }
}

