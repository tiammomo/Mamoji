package com.mamoji.dto;

import lombok.Data;

/**
 * Chat request payload for AI assistant endpoints.
 */
@Data
public class AIChatRequest {
    private String message;
    private String assistantType; // finance, stock
    private String sessionId;
    private String mode; // llm, agent, auto
}
