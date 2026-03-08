package com.mamoji.ai.tool;

import com.mamoji.agent.tool.finance.FinanceTools;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class FinanceAiToolHandler implements AiToolHandler {

    private final FinanceTools financeTools;

    @Override
    public String name() {
        return "finance";
    }

    @Override
    public AiToolResult execute(Long userId, Map<String, Object> params) {
        String operation = stringParam(params, "operation");
        if (operation == null || operation.isBlank()) {
            return AiToolResult.fail("finance", "operation is required");
        }

        return switch (operation) {
            case "query_income_expense" -> AiToolResult.ok("finance.query_income_expense",
                financeTools.queryIncomeExpense(userId, stringParam(params, "startDate"), stringParam(params, "endDate")));
            case "query_budget" -> AiToolResult.ok("finance.query_budget",
                financeTools.queryBudget(userId, longParam(params, "budgetId")));
            case "query_transactions" -> AiToolResult.ok("finance.query_transactions",
                financeTools.queryTransactions(
                    userId,
                    stringParam(params, "startDate"),
                    stringParam(params, "endDate"),
                    longParam(params, "categoryId"),
                    intParam(params, "type")
                ));
            case "query_category_stats" -> AiToolResult.ok("finance.query_category_stats",
                financeTools.queryCategoryStats(
                    userId,
                    stringParam(params, "startDate"),
                    stringParam(params, "endDate"),
                    intParamOrDefault(params, "type", 2)
                ));
            default -> AiToolResult.fail("finance." + operation, "unsupported finance operation");
        };
    }

    private String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : value.toString();
    }

    private Long longParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer intParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer intParamOrDefault(Map<String, Object> params, String key, int defaultValue) {
        Integer value = intParam(params, key);
        return value != null ? value : defaultValue;
    }
}
