package com.mamoji.workforce.api;

import java.math.BigDecimal;
import java.util.List;

public record WorkforceCostView(
    long companyId,
    String companyName,
    String period,
    String source,
    Long payrollRunId,
    String payrollRunStatus,
    Headcount headcount,
    CostSummary costs,
    List<DepartmentCost> departments,
    List<TrendPoint> trend,
    List<AttentionItem> attentionItems
) {
    public record Headcount(
        int active,
        int probation,
        int onboarding,
        int departedThisMonth,
        int costed
    ) {
    }

    public record CostSummary(
        BigDecimal salary,
        BigDecimal overtime,
        BigDecimal employerSocial,
        BigDecimal employerHousing,
        BigDecimal other,
        BigDecimal total,
        BigDecimal average,
        BigDecimal operatingExpense,
        BigDecimal operatingExpenseShare
    ) {
    }

    public record DepartmentCost(
        Long departmentId,
        String departmentName,
        int headcount,
        BigDecimal salary,
        BigDecimal overtime,
        BigDecimal employerSocial,
        BigDecimal employerHousing,
        BigDecimal other,
        BigDecimal total,
        BigDecimal average,
        BigDecimal share,
        BigDecimal budget,
        BigDecimal budgetVariance,
        BigDecimal budgetUsageRate
    ) {
    }

    public record TrendPoint(
        String period,
        BigDecimal total,
        int headcount,
        BigDecimal average,
        String status
    ) {
    }

    public record AttentionItem(String code, String title, String detail, String severity, String path) {
    }
}
