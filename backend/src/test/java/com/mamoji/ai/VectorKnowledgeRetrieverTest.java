package com.mamoji.ai;

import com.mamoji.ai.rag.FileKnowledgeRetriever;
import com.mamoji.ai.rag.KnowledgeSnippet;
import com.mamoji.ai.rag.LocalKnowledgeRetriever;
import com.mamoji.ai.rag.VectorKnowledgeRetriever;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

/**
 * Test suite for VectorKnowledgeRetrieverTest.
 */

class VectorKnowledgeRetrieverTest {

    @Test
    void shouldUseVectorResultsWhenAvailable() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<FileKnowledgeRetriever> fileProvider = Mockito.mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LocalKnowledgeRetriever> localProvider = Mockito.mock(ObjectProvider.class);

        Mockito.when(vectorStore.similaritySearch("budget plan"))
            .thenReturn(List.of(new Document(
                "Keep spending ratio below 70%.",
                Map.of("source", "vector:policy", "title", "Budget Rule", "assistantType", "finance")
            )));

        VectorKnowledgeRetriever retriever = new VectorKnowledgeRetriever(vectorStore, fileProvider, localProvider);
        List<KnowledgeSnippet> snippets = retriever.retrieve("finance", "budget plan", 2);

        Assertions.assertEquals(1, snippets.size());
        Assertions.assertEquals("vector:policy", snippets.get(0).source());
        Assertions.assertEquals("Budget Rule", snippets.get(0).title());
    }

    @Test
    void shouldFallbackToFileRetrieverWhenVectorReturnsEmpty() {
        VectorStore vectorStore = Mockito.mock(VectorStore.class);
        FileKnowledgeRetriever fileRetriever = Mockito.mock(FileKnowledgeRetriever.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<FileKnowledgeRetriever> fileProvider = Mockito.mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LocalKnowledgeRetriever> localProvider = Mockito.mock(ObjectProvider.class);

        Mockito.when(vectorStore.similaritySearch("market")).thenReturn(List.of());
        Mockito.when(fileProvider.getIfAvailable()).thenReturn(fileRetriever);
        Mockito.when(fileRetriever.retrieve("stock", "market", 1))
            .thenReturn(List.of(new KnowledgeSnippet("file", "Market Rule", "Always mention uncertainty.")));

        VectorKnowledgeRetriever retriever = new VectorKnowledgeRetriever(vectorStore, fileProvider, localProvider);
        List<KnowledgeSnippet> snippets = retriever.retrieve("stock", "market", 1);

        Assertions.assertEquals(1, snippets.size());
        Assertions.assertEquals("Market Rule", snippets.get(0).title());
    }
}



