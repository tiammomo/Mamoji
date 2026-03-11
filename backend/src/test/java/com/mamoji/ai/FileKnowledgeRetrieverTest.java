package com.mamoji.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.ai.rag.FileKnowledgeRetriever;
import com.mamoji.ai.rag.KnowledgeSnippet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

/**
 * Test suite for FileKnowledgeRetrieverTest.
 */

class FileKnowledgeRetrieverTest {

    @Test
    void shouldReturnRankedFinanceKnowledge() {
        AiProperties properties = new AiProperties();
        properties.getRagOps().setKnowledgePath("classpath:ai/knowledge-base.json");

        FileKnowledgeRetriever retriever = new FileKnowledgeRetriever(
            properties,
            new DefaultResourceLoader(),
            new ObjectMapper()
        );

        List<KnowledgeSnippet> snippets = retriever.retrieve("finance", "budget risk and reduction plan", 2);

        Assertions.assertEquals(2, snippets.size());
        Assertions.assertEquals("policy/finance-3", snippets.get(0).source());
    }

    @Test
    void shouldFilterByAssistantType() {
        AiProperties properties = new AiProperties();
        properties.getRagOps().setKnowledgePath("classpath:ai/knowledge-base.json");

        FileKnowledgeRetriever retriever = new FileKnowledgeRetriever(
            properties,
            new DefaultResourceLoader(),
            new ObjectMapper()
        );

        List<KnowledgeSnippet> snippets = retriever.retrieve("stock", "quote recency timestamp", 3);

        Assertions.assertFalse(snippets.isEmpty());
        Assertions.assertTrue(snippets.stream().anyMatch(s -> "policy/stock-4".equals(s.source())));
        Assertions.assertTrue(snippets.stream().noneMatch(s -> "policy/finance-3".equals(s.source())));
    }
}



