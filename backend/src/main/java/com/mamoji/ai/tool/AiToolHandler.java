package com.mamoji.ai.tool;

import java.util.Map;

/**
 * Contract for executable AI tool handlers.
 */
public interface AiToolHandler {

    /**
     * Unique tool namespace name.
     */
    String name();

    /**
     * Executes tool request and returns normalized result.
     */
    AiToolResult execute(Long userId, Map<String, Object> params);
}

