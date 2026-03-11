package com.mamoji.ai.rag;

/**
 * Retrieved knowledge snippet payload.
 */
public record KnowledgeSnippet(
    String source,
    String title,
    String content
) {
}

