package com.mamoji.ai.prompt;

import com.mamoji.ai.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Selects prompt variants for experimentation and quality tuning.
 */
@Service
@RequiredArgsConstructor
public class PromptVariantService {

    private static final String FINANCE_A = """
        You are a professional family finance assistant.
        Output language: Simplified Chinese.
        Grounding priority: tool result > retrieved knowledge > conversation memory.
        Do not fabricate numbers. If data is missing, explicitly say "暂无数据".
        Output must be practical and concise, and include this structure in answer:
        1) 结论：一句话总结
        2) 关键数据：2~4条 "- 指标：数值（口径/时间）"
        3) 建议：1~3条可执行动作（含时间或阈值）
        """;

    private static final String FINANCE_B = """
        You are a senior household CFO assistant for finance planning.
        Output language: Simplified Chinese.
        Always reference available figures and periods. Avoid generic statements.
        If the user asks for analysis, provide assumptions and explicit next steps.
        Answer structure must include:
        结论 / 关键数据 / 建议.
        """;

    private static final String STOCK_A = """
        You are a stock assistant.
        Output language: Simplified Chinese.
        Grounding priority: tool market data > retrieved knowledge.
        Never promise returns. Include a clear risk disclaimer in every response.
        Structure answer as:
        结论 / 关键数据 / 建议.
        """;

    private static final String STOCK_B = """
        You are a cautious stock research assistant.
        Output language: Simplified Chinese.
        Explain uncertainty and data recency when quote/news data is incomplete.
        Always include "投资有风险，决策需谨慎".
        Structure answer as:
        结论 / 关键数据 / 建议.
        """;

    private final AiProperties aiProperties;

    /**
     * Picks prompt variant by assistant type and session-key bucket.
     */
    public PromptVariant pick(String assistantType, String sessionKey) {
        String normalizedKey = sessionKey != null ? sessionKey : "default";
        String type = "stock".equalsIgnoreCase(assistantType) ? "stock" : "finance";
        int bucket = Math.floorMod(normalizedKey.hashCode(), 100);
        AiProperties.PromptOps promptOps = aiProperties.getPromptOps();

        boolean useA = useVariantA(type, bucket, promptOps);
        String experimentId = promptOps.getExperimentId();
        String variant = useA ? "A" : "B";
        String systemPrompt = resolveSystemPrompt(type, variant);

        return new PromptVariant(variant, systemPrompt, experimentId, bucket);
    }

    /**
     * Determines whether variant A should be selected for this bucket.
     */
    private boolean useVariantA(String type, int bucket, AiProperties.PromptOps promptOps) {
        if (!promptOps.isEnabled()) {
            return !"B".equalsIgnoreCase(promptOps.getDefaultVariant());
        }
        int weight = "stock".equals(type) ? promptOps.getStockVariantAWeight() : promptOps.getFinanceVariantAWeight();
        int safeWeight = Math.max(0, Math.min(weight, 100));
        return bucket < safeWeight;
    }

    /**
     * Resolves concrete prompt template by assistant type and variant id.
     */
    private String resolveSystemPrompt(String type, String variant) {
        if ("stock".equals(type)) {
            return "A".equalsIgnoreCase(variant) ? STOCK_A : STOCK_B;
        }
        return "A".equalsIgnoreCase(variant) ? FINANCE_A : FINANCE_B;
    }

    /**
     * Prompt-selection output payload.
     */
    public record PromptVariant(String variant, String systemPrompt, String experimentId, int bucket) {
    }
}
