package com.mamoji.workspace.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public record WorkspaceView(
    long companyId,
    String companyName,
    String period,
    int score,
    String severity,
    Set<String> capabilities,
    Metrics metrics,
    List<ModuleHealth> modules,
    List<ActionItem> priorityActions,
    List<DailyCheck> dailyChecks,
    List<BudgetRisk> budgetRisks,
    List<RecentTransaction> recentTransactions,
    List<UpcomingItem> upcomingItems
) {
    public record Metrics(
        BigDecimal monthlyIncome,
        BigDecimal monthlyExpense,
        BigDecimal monthlyProfit,
        BigDecimal availableCash,
        BigDecimal budgetAmount,
        BigDecimal budgetSpent,
        BigDecimal budgetUsageRate,
        int pendingApprovalCount,
        int accountIssueCount,
        int evidenceIssueCount,
        int overdueRecurringCount,
        int reviewTransactionCount
    ) {
    }

    public record ModuleHealth(String key, String title, int score, String severity, String detail, String path) {
    }

    public record ActionItem(String code, String title, String detail, String severity, String path) {
    }

    public record DailyCheck(String key, String label, boolean done, String detail, String path) {
    }

    public record BudgetRisk(long id, String name, BigDecimal amount, BigDecimal spent, double usageRate, String riskLevel) {
    }

    public record RecentTransaction(
        long id,
        int type,
        BigDecimal amount,
        String date,
        String note,
        String categoryName,
        String accountName
    ) {
    }

    public record UpcomingItem(String id, String title, String dueDate, boolean overdue, String path) {
    }
}
