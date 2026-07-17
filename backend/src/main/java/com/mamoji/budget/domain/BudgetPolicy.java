package com.mamoji.budget.domain;

import com.mamoji.domain.Models.Budget;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class BudgetPolicy {
    public Budget apply(Budget budget) {
        budget.spent = zeroIfNull(budget.spent).max(BigDecimal.ZERO);
        if (budget.amount == null || budget.amount.compareTo(BigDecimal.ZERO) <= 0) {
            budget.remainingAmount = BigDecimal.ZERO;
            budget.usageRate = 0;
        } else {
            budget.remainingAmount = budget.amount.subtract(budget.spent);
            budget.usageRate = budget.spent.divide(budget.amount, 4, RoundingMode.HALF_UP).doubleValue();
        }
        budget.warningReached = budget.usageRate * 100 >= budget.warningThreshold;
        boolean mutableLifecycle = budget.status != 0 && budget.status != 2;
        if (budget.usageRate >= 1) {
            budget.riskLevel = "critical";
            budget.riskMessage = "预算已超支";
            if (mutableLifecycle) budget.status = 3;
        } else if (budget.warningReached) {
            budget.riskLevel = "high";
            budget.riskMessage = "接近预算上限";
            if (mutableLifecycle && budget.status == 3) budget.status = 1;
        } else if (budget.usageRate >= 0.6) {
            budget.riskLevel = "medium";
            budget.riskMessage = "使用进度正常偏高";
            if (mutableLifecycle && budget.status == 3) budget.status = 1;
        } else {
            budget.riskLevel = "low";
            budget.riskMessage = "预算健康";
            if (mutableLifecycle && budget.status == 3) budget.status = 1;
        }
        return budget;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
