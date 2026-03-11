package com.mamoji.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Minimal AI chat response payload.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AIChatResponse {
    private String reply;
}
