package com.mamoji.ai.quality;

import com.mamoji.ai.AiProperties;
import com.mamoji.ai.metrics.AiMetricsService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rule-based quality checker for AI answers.
 */
@Service
public class AiQualityGateService {

    private final AiProperties aiProperties;
    private final AiMetricsService aiMetricsService;

    public AiQualityGateService(AiProperties aiProperties, AiMetricsService aiMetricsService) {
        this.aiProperties = aiProperties;
        this.aiMetricsService = aiMetricsService;
    }

    /**
     * Validates answer content and returns warning rule ids.
     */
    public List<String> validate(String assistantType, String question, String answer) {
        String type = normalizeAssistantType(assistantType);
        AiProperties.QualityOps qualityOps = aiProperties.getQualityOps();
        List<String> warnings = new ArrayList<>();

        applyRule(answer == null || answer.isBlank(), warnings, type, "empty_answer");
        if (!warnings.isEmpty()) {
            return warnings;
        }

        applyRule(answer.length() < Math.max(1, qualityOps.getMinAnswerLength()), warnings, type, "too_short");
        applyRule(
            question != null && question.length() > Math.max(100, qualityOps.getMaxQuestionLength()),
            warnings,
            type,
            "question_too_long"
        );
        if ("stock".equals(type) && qualityOps.isStockRiskRequired()) {
            applyRule(!containsRiskWarning(answer), warnings, type, "missing_risk_warning");
        }
        if ("finance".equals(type) && qualityOps.isFinanceActionableRequired()) {
            applyRule(!containsActionableHint(answer), warnings, type, "missing_actionable_advice");
        }
        if (qualityOps.isRecencyStatementRequired()) {
            applyRule(!containsRecencyStatement(answer), warnings, type, "missing_recency_statement");
        }

        return warnings;
    }

    /**
     * Adds warning when condition is met and records metric hit.
     */
    private void applyRule(boolean condition, List<String> warnings, String assistantType, String rule) {
        if (!condition) {
            return;
        }
        warnings.add(rule);
        aiMetricsService.recordQualityRuleHit(assistantType, rule);
    }

    /**
     * Detects stock-risk disclaimer words.
     */
    private boolean containsRiskWarning(String answer) {
        String text = normalize(answer);
        return text.contains("风险")
            || text.contains("谨慎")
            || text.contains("不构成投资建议")
            || text.contains("投资有风险");
    }

    /**
     * Detects actionable-advice hints in finance responses.
     */
    private boolean containsActionableHint(String answer) {
        String text = normalize(answer);
        return text.contains("建议")
            || text.contains("可以")
            || text.contains("步骤")
            || text.contains("先")
            || text.contains("然后")
            || text.contains("本周")
            || text.contains("本月");
    }

    /**
     * Detects recency statement hints.
     */
    private boolean containsRecencyStatement(String answer) {
        String text = normalize(answer);
        return text.contains("截至")
            || text.contains("数据时间")
            || text.contains("更新时间")
            || text.contains("as of");
    }

    /**
     * Lowercases text safely.
     */
    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    /**
     * Normalizes assistant type to finance/stock.
     */
    private String normalizeAssistantType(String assistantType) {
        return "stock".equalsIgnoreCase(assistantType) ? "stock" : "finance";
    }
}
