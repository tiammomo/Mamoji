package com.mamoji.ai.rag;

import java.util.List;

/**
 * Retrieval abstraction for knowledge snippets used in RAG prompts.
 */
public interface KnowledgeRetriever {

    /**
     * Retrieves top-k snippets for assistant type and question.
     */
    List<KnowledgeSnippet> retrieve(String assistantType, String question, int topK);
}

