package com.mamoji.ai;

import org.springframework.stereotype.Service;

@Service
public class AiModelRouter {

    private final AiProperties aiProperties;

    public AiModelRouter(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    public String pickPrimaryModel(String assistantType, String question) {
        return pickPrimaryModelDecision(assistantType, question).model();
    }

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

    private boolean isHighComplexity(String question, AiProperties.RoutingOps routingOps) {
        if (question == null) {
            return false;
        }
        return question.length() >= Math.max(100, routingOps.getHighComplexityQuestionChars());
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public record RoutingDecision(String model, String reason) {
    }
}
