package com.mamoji.ai.rag;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in fallback retriever with static policy snippets.
 */
@Component
@ConditionalOnMissingBean(KnowledgeRetriever.class)
public class LocalKnowledgeRetriever implements KnowledgeRetriever {

    private static final List<KnowledgeSnippet> FINANCE_KNOWLEDGE = List.of(
        new KnowledgeSnippet(
            "policy/finance-1",
            "Budget Safety Rule",
            "When monthly spending ratio exceeds 85%, provide concrete reduction suggestions and list the top spending categories."
        ),
        new KnowledgeSnippet(
            "policy/finance-2",
            "Advice Precision Rule",
            "Finance suggestions must include numbers, period references, and at least one actionable step."
        )
    );

    private static final List<KnowledgeSnippet> STOCK_KNOWLEDGE = List.of(
        new KnowledgeSnippet(
            "policy/stock-1",
            "Risk Warning Rule",
            "Every stock recommendation must include risk notice and avoid guaranteed return expressions."
        ),
        new KnowledgeSnippet(
            "policy/stock-2",
            "Data Recency Rule",
            "If real-time quote is unavailable, explicitly mention data recency limitations."
        )
    );

    /**
     * Returns top-k from built-in knowledge snippets.
     */
    @Override
    public List<KnowledgeSnippet> retrieve(String assistantType, String question, int topK) {
        List<KnowledgeSnippet> base = "stock".equals(assistantType) ? STOCK_KNOWLEDGE : FINANCE_KNOWLEDGE;
        int limit = Math.max(0, Math.min(topK, base.size()));
        return new ArrayList<>(base.subList(0, limit));
    }
}
