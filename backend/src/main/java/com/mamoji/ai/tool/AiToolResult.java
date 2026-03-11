package com.mamoji.ai.tool;

/**
 * Standard result envelope for tool invocation.
 */
public record AiToolResult(
    boolean success,
    String toolName,
    String payload,
    String error
) {

    /**
     * Builds successful tool result.
     */
    public static AiToolResult ok(String toolName, String payload) {
        return new AiToolResult(true, toolName, payload, null);
    }

    /**
     * Builds failed tool result.
     */
    public static AiToolResult fail(String toolName, String error) {
        return new AiToolResult(false, toolName, null, error);
    }
}

