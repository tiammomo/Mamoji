package com.mamoji.ai;

import org.springframework.stereotype.Service;

/**
 * Selects primary model according to assistant type and routing policy.
 */
@Service
public class AiModelRouter {

    private final AiProperties aiProperties;

    public AiModelRouter(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    /**
     * Returns selected model name only.
     */
    public String pickPrimaryModel(String assistantType, String question) {
        return pickPrimaryModelDecision(assistantType, question).model();
    }

    /**
     * Returns model selection and routing reason.
     */
    public RoutingDecision pickPrimaryModelDecision(String assistantType, String question) {
        AiProperties.RoutingOps routingOps = aiProperties.getRoutingOps();
        if (!routingOps.isEnabled()) {
            return new RoutingDecision(aiProperties.getModel(), "routing_disabled");
        }

        if (isHighComplexity(question, routingOps) && notBlank(routingOps.getHighComplexityModel())) {
            return new RoutingDecision(routingOps.getHighComplexityModel().trim(), "high_complexity");
        }

        if ("stock".equalsIgnoreCase(assistantType) && notBlank(routingOps.getStockModel())) {
            return new RoutingDecision(routingOps.getStockModel().trim(), "assistant_type_stock");
        }

        if ("finance".equalsIgnoreCase(assistantType) && notBlank(routingOps.getFinanceModel())) {
            return new RoutingDecision(routingOps.getFinanceModel().trim(), "assistant_type_finance");
        }

        return new RoutingDecision(aiProperties.getModel(), "default");
    }

    /**
     * Determines whether question is high-complexity by length threshold.
     */
    private boolean isHighComplexity(String question, AiProperties.RoutingOps routingOps) {
        if (question == null) {
            return false;
        }
        return question.length() >= Math.max(100, routingOps.getHighComplexityQuestionChars());
    }

    /**
     * Checks non-blank text.
     */
    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Immutable routing decision payload.
     */
    public record RoutingDecision(String model, String reason) {
    }
}
