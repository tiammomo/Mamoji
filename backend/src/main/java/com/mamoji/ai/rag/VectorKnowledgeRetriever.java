package com.mamoji.ai.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Primary
@Component
@RequiredArgsConstructor
@ConditionalOnBean(VectorStore.class)
public class VectorKnowledgeRetriever implements KnowledgeRetriever {

    private final VectorStore vectorStore;
    private final ObjectProvider<FileKnowledgeRetriever> fileKnowledgeRetrieverProvider;
    private final ObjectProvider<LocalKnowledgeRetriever> localKnowledgeRetrieverProvider;

    @Override
    public List<KnowledgeSnippet> retrieve(String assistantType, String question, int topK) {
        int limit = Math.max(0, topK);
        if (limit == 0 || question == null || question.isBlank()) {
            return fallbackRetrieve(assistantType, question, topK);
        }

        List<KnowledgeSnippet> vectorResults = fromVectorStore(assistantType, question, limit);
        if (!vectorResults.isEmpty()) {
            return vectorResults;
        }

        return fallbackRetrieve(assistantType, question, topK);
    }

    private List<KnowledgeSnippet> fromVectorStore(String assistantType, String question, int topK) {
        List<Document> documents = vectorStore.similaritySearch(question);
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        String normalizedType = normalizeType(assistantType);
        List<KnowledgeSnippet> snippets = new ArrayList<>();
        for (Document document : documents) {
            if (document == null) {
                continue;
            }
            if (!supportsType(document.getMetadata(), normalizedType)) {
                continue;
            }
            String text = document.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            String source = readMetadata(document.getMetadata(), "source", "vector:" + safe(document.getId()));
            String title = readMetadata(document.getMetadata(), "title", "Vector Knowledge");
            snippets.add(new KnowledgeSnippet(source, title, text));
            if (snippets.size() >= topK) {
                break;
            }
        }
        return snippets;
    }

    private List<KnowledgeSnippet> fallbackRetrieve(String assistantType, String question, int topK) {
        FileKnowledgeRetriever fileRetriever = fileKnowledgeRetrieverProvider.getIfAvailable();
        if (fileRetriever != null) {
            List<KnowledgeSnippet> snippets = fileRetriever.retrieve(assistantType, question, topK);
            if (!snippets.isEmpty()) {
                return snippets;
            }
        }

        LocalKnowledgeRetriever localRetriever = localKnowledgeRetrieverProvider.getIfAvailable();
        if (localRetriever != null) {
            return localRetriever.retrieve(assistantType, question, topK);
        }

        return List.of();
    }

    private boolean supportsType(Map<String, Object> metadata, String assistantType) {
        if (metadata == null || metadata.isEmpty()) {
            return true;
        }
        String docType = readMetadata(metadata, "assistantType", "all").trim().toLowerCase(Locale.ROOT);
        return "all".equals(docType) || assistantType.equals(docType);
    }

    private String normalizeType(String assistantType) {
        if ("stock".equalsIgnoreCase(assistantType)) {
            return "stock";
        }
        return "finance";
    }

    private String readMetadata(Map<String, Object> metadata, String key, String fallback) {
        if (metadata == null) {
            return fallback;
        }
        Object value = metadata.get(key);
        if (value == null) {
            return fallback;
        }
        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    private String safe(String value) {
        return value == null ? "unknown" : value;
    }
}
