package com.mamoji.workspace.application;

import com.mamoji.budget.application.BudgetApplicationService;
import com.mamoji.domain.Models.Budget;
import com.mamoji.platform.access.AccessContextService;
import com.mamoji.platform.access.AccessContextView;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.workspace.api.WorkspaceView;
import com.mamoji.workspace.api.WorkspaceView.ActionItem;
import com.mamoji.workspace.api.WorkspaceView.BudgetRisk;
import com.mamoji.workspace.api.WorkspaceView.DailyCheck;
import com.mamoji.workspace.api.WorkspaceView.Metrics;
import com.mamoji.workspace.api.WorkspaceView.ModuleHealth;
import com.mamoji.workspace.infrastructure.WorkspaceReadRepository;
import com.mamoji.workspace.infrastructure.WorkspaceReadRepository.DataScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceApplicationService {
    private final AccessContextService accessContextService;
    private final BudgetApplicationService budgetService;
    private final WorkspaceReadRepository repository;

    public WorkspaceApplicationService(
        AccessContextService accessContextService,
        BudgetApplicationService budgetService,
        WorkspaceReadRepository repository
    ) {
        this.accessContextService = accessContextService;
        this.budgetService = budgetService;
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkspaceView view(ActorContext actor, Long companyId) {
        AccessContextView access = accessContextService.resolve(actor, companyId);
        long selectedCompanyId = access.company().id;
        Set<String> permissions = access.permissions();
        boolean companyWide = companyWideScope(access.scope());
        Long scopedDepartmentId = "department".equals(access.scope()) ? access.departmentId() : null;
        DataScope dataScope = new DataScope(actor.userId(), scopedDepartmentId, companyWide);
        boolean operationsReadable = hasAny(permissions, "operations.read", "operations.write", "reports.read");
        boolean financeReadable = companyWide && hasAny(permissions, "finance.read", "finance.write");
        boolean budgetReadable = hasAny(permissions, "budget.manage", "operations.read", "reports.read");
        boolean workflowReadable = permissions.contains("approval.manage");
        boolean recurringReadable = operationsReadable && access.modules().isEnabled("recurring");

        YearMonth period = YearMonth.now();
        LocalDate today = LocalDate.now();
        var operating = operationsReadable
            ? repository.operatingMetrics(selectedCompanyId, period.atDay(1), period.atEndOfMonth(), dataScope)
            : null;
        var finance = financeReadable ? repository.financeMetrics(selectedCompanyId) : null;
        int evidenceIssues = financeReadable && access.modules().isEnabled("evidence")
            ? repository.evidenceIssueCount(selectedCompanyId) : 0;
        int pendingApprovals = workflowReadable
            ? repository.pendingApprovalCount(selectedCompanyId, actor.userId(), companyWide) : 0;
        var recurring = recurringReadable
            ? repository.recurringMetrics(selectedCompanyId, today, dataScope)
            : new WorkspaceReadRepository.RecurringMetrics(0, 0);
        List<Budget> budgets = budgetReadable ? budgetService.companyBudgets(selectedCompanyId) : List.of();
        if (!companyWide) {
            budgets = budgets.stream().filter(budget -> budget.userId == actor.userId()).toList();
        }
        List<Budget> activeBudgets = budgets.stream().filter(budget -> budget.status != 0 && budget.status != 2).toList();
        BigDecimal budgetAmount = sum(activeBudgets, budget -> budget.amount);
        BigDecimal budgetSpent = sum(activeBudgets, budget -> budget.spent);
        BigDecimal budgetUsage = budgetAmount.signum() == 0
            ? BigDecimal.ZERO
            : budgetSpent.divide(budgetAmount, 4, RoundingMode.HALF_UP);
        List<BudgetRisk> budgetRisks = activeBudgets.stream()
            .filter(budget -> "high".equals(budget.riskLevel) || "critical".equals(budget.riskLevel))
            .map(budget -> new BudgetRisk(
                budget.id, budget.name, budget.amount, budget.spent, budget.usageRate, budget.riskLevel
            ))
            .limit(8)
            .toList();

        BigDecimal income = operating == null ? null : operating.income();
        BigDecimal expense = operating == null ? null : operating.expense();
        BigDecimal profit = operating == null ? null : income.subtract(expense);
        BigDecimal availableCash = finance == null ? null : finance.availableCash();
        int reviewTransactions = operating == null ? 0 : operating.reviewCount();
        int accountIssues = finance == null ? 0 : finance.issueCount();

        List<ModuleHealth> modules = moduleHealth(
            operationsReadable, financeReadable, budgetReadable, workflowReadable,
            profit, expense, availableCash, reviewTransactions, accountIssues,
            evidenceIssues, budgetRisks.size(), pendingApprovals, recurring.overdueCount()
        );
        int score = modules.isEmpty()
            ? 100
            : (int) Math.round(modules.stream().mapToInt(ModuleHealth::score).average().orElse(100));
        String severity = severity(score);
        List<ActionItem> actions = priorityActions(
            operationsReadable, financeReadable, budgetReadable, workflowReadable,
            profit, expense, availableCash, reviewTransactions, accountIssues,
            evidenceIssues, budgetRisks.size(), pendingApprovals, recurring.overdueCount()
        );
        List<DailyCheck> checks = dailyChecks(
            operationsReadable, financeReadable, budgetReadable, workflowReadable,
            reviewTransactions, accountIssues, evidenceIssues, budgetRisks.size(), pendingApprovals
        );
        LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        if (operationsReadable) capabilities.add("operations");
        if (financeReadable) capabilities.add("finance");
        if (budgetReadable) capabilities.add("budget");
        if (workflowReadable) capabilities.add("workflow");

        return new WorkspaceView(
            selectedCompanyId,
            access.company().name,
            period.toString(),
            score,
            severity,
            Set.copyOf(capabilities),
            new Metrics(
                income, expense, profit, availableCash,
                budgetReadable ? budgetAmount : null,
                budgetReadable ? budgetSpent : null,
                budgetReadable ? budgetUsage : null,
                pendingApprovals, accountIssues, evidenceIssues,
                recurring.overdueCount(), reviewTransactions
            ),
            modules,
            actions,
            checks,
            budgetRisks,
            operationsReadable ? repository.recentTransactions(selectedCompanyId, 8, dataScope) : List.of(),
            recurringReadable ? repository.upcomingItems(selectedCompanyId, today, 6, dataScope) : List.of()
        );
    }

    private List<ModuleHealth> moduleHealth(
        boolean operations, boolean finance, boolean budget, boolean workflow,
        BigDecimal profit, BigDecimal expense, BigDecimal cash,
        int transactionIssues, int accountIssues, int evidenceIssues,
        int budgetIssues, int pendingApprovals, int overdueRecurring
    ) {
        List<ModuleHealth> modules = new ArrayList<>();
        if (operations) {
            int value = clamp(100 - (negative(profit) ? 25 : 0) - transactionIssues * 5 - overdueRecurring * 6);
            modules.add(module("operations", "经营管理", value,
                transactionIssues + " 笔流水待复核 · " + overdueRecurring + " 项周期事项逾期", "/operations"));
        }
        if (budget) {
            int value = clamp(100 - budgetIssues * 12);
            modules.add(module("budget", "预算控制", value, budgetIssues + " 项预算触发预警", "/budgets"));
        }
        if (finance) {
            int cashPenalty = expense != null && cash != null && cash.compareTo(expense) < 0 ? 15 : 0;
            int value = clamp(100 - accountIssues * 8 - evidenceIssues * 5 - cashPenalty);
            modules.add(module("finance", "资金与凭证", value,
                accountIssues + " 个账户问题 · " + evidenceIssues + " 个凭证问题", "/finance"));
        }
        if (workflow) {
            int value = clamp(100 - Math.min(pendingApprovals * 3, 30));
            modules.add(module("workflow", "审批协同", value, pendingApprovals + " 项待处理审批", "/approvals"));
        }
        return modules;
    }

    private List<ActionItem> priorityActions(
        boolean operations, boolean finance, boolean budget, boolean workflow,
        BigDecimal profit, BigDecimal expense, BigDecimal cash,
        int transactionIssues, int accountIssues, int evidenceIssues,
        int budgetIssues, int pendingApprovals, int overdueRecurring
    ) {
        List<ActionItem> actions = new ArrayList<>();
        if (workflow && pendingApprovals > 0) {
            actions.add(action("approval.pending", "审批待处理", pendingApprovals + " 项申请等待处理", "notice", "/approvals"));
        }
        if (operations && negative(profit)) {
            actions.add(action("operations.loss", "本月经营净额为负", "建议复核收入、成本和一次性支出", "danger", "/operations"));
        }
        if (budget && budgetIssues > 0) {
            actions.add(action("budget.risk", "预算需要复核", budgetIssues + " 项预算接近上限或已超支", "warning", "/budgets"));
        }
        if (finance && accountIssues > 0) {
            actions.add(action("finance.reconcile", "资金账户需要对账", accountIssues + " 个账户存在对账或风险问题", "warning", "/accounts"));
        }
        if (finance && evidenceIssues > 0) {
            actions.add(action("evidence.incomplete", "票据凭证未闭环", evidenceIssues + " 个凭证需要审核、入账或补附件", "warning", "/receipts"));
        }
        if (finance && expense != null && cash != null && cash.compareTo(expense) < 0) {
            actions.add(action("finance.cash.coverage", "可用资金覆盖不足", "当前可用资金低于本月成本", "danger", "/accounts"));
        }
        if (operations && transactionIssues > 0) {
            actions.add(action("operations.review", "经营流水需要复核", transactionIssues + " 笔大额或缺少说明的流水待确认", "notice", "/transactions"));
        }
        if (operations && overdueRecurring > 0) {
            actions.add(action("recurring.overdue", "周期事项已逾期", overdueRecurring + " 项周期事项尚未处理", "danger", "/recurring"));
        }
        if (actions.isEmpty()) {
            actions.add(action("workspace.healthy", "工作台状态良好", "当前权限范围内暂无明显风险待办", "success", "/dashboard"));
        }
        return actions.stream().limit(6).toList();
    }

    private List<DailyCheck> dailyChecks(
        boolean operations, boolean finance, boolean budget, boolean workflow,
        int transactionIssues, int accountIssues, int evidenceIssues, int budgetIssues, int pendingApprovals
    ) {
        List<DailyCheck> checks = new ArrayList<>();
        if (operations) checks.add(check("transactions", "经营流水日清", transactionIssues == 0, transactionIssues + " 笔待复核", "/transactions"));
        if (budget) checks.add(check("budgets", "预算预警复核", budgetIssues == 0, budgetIssues + " 项需关注", "/budgets"));
        if (finance) {
            checks.add(check("accounts", "资金账户对账", accountIssues == 0, accountIssues + " 个问题", "/accounts"));
            checks.add(check("evidence", "票据凭证闭环", evidenceIssues == 0, evidenceIssues + " 个问题", "/receipts"));
        }
        if (workflow) checks.add(check("approvals", "审批待办清理", pendingApprovals == 0, pendingApprovals + " 项待处理", "/approvals"));
        return checks;
    }

    private ModuleHealth module(String key, String title, int score, String detail, String path) {
        return new ModuleHealth(key, title, score, severity(score), detail, path);
    }

    private ActionItem action(String code, String title, String detail, String severity, String path) {
        return new ActionItem(code, title, detail, severity, path);
    }

    private DailyCheck check(String key, String label, boolean done, String detail, String path) {
        return new DailyCheck(key, label, done, done ? "已完成" : detail, path);
    }

    private String severity(int score) {
        if (score >= 85) return "success";
        if (score >= 70) return "notice";
        if (score >= 55) return "warning";
        return "danger";
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private boolean negative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    private boolean hasAny(Set<String> permissions, String... candidates) {
        for (String candidate : candidates) {
            if (permissions.contains(candidate)) return true;
        }
        return false;
    }

    private boolean companyWideScope(String scope) {
        return Set.of("group", "company", "company_set", "readonly").contains(scope);
    }

    private BigDecimal sum(List<Budget> budgets, java.util.function.Function<Budget, BigDecimal> mapper) {
        return budgets.stream().map(mapper).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
