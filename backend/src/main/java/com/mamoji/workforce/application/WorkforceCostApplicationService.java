package com.mamoji.workforce.application;

import com.mamoji.platform.access.AccessContextService;
import com.mamoji.platform.access.AccessContextView;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.workforce.api.WorkforceCostView;
import com.mamoji.workforce.api.WorkforceCostView.AttentionItem;
import com.mamoji.workforce.api.WorkforceCostView.CostSummary;
import com.mamoji.workforce.api.WorkforceCostView.DepartmentCost;
import com.mamoji.workforce.api.WorkforceCostView.Headcount;
import com.mamoji.workforce.api.WorkforceCostView.TrendPoint;
import com.mamoji.workforce.infrastructure.WorkforceCostReadRepository;
import com.mamoji.workforce.infrastructure.WorkforceCostReadRepository.CostAggregate;
import com.mamoji.workforce.infrastructure.WorkforceCostReadRepository.DataScope;
import com.mamoji.workforce.infrastructure.WorkforceCostReadRepository.DepartmentAggregate;
import com.mamoji.workforce.infrastructure.WorkforceCostReadRepository.PayrollRunRef;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkforceCostApplicationService {
    private static final Set<String> COMPANY_WIDE_SCOPES = Set.of("group", "company", "company_set", "readonly");

    private final AccessContextService accessContextService;
    private final WorkforceCostReadRepository repository;

    public WorkforceCostApplicationService(
        AccessContextService accessContextService,
        WorkforceCostReadRepository repository
    ) {
        this.accessContextService = accessContextService;
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkforceCostView view(ActorContext actor, Long companyId, String requestedPeriod) {
        AccessContextView access = accessContextService.resolve(actor, companyId);
        requireReadPermission(access);
        YearMonth period = parsePeriod(requestedPeriod);
        long selectedCompanyId = access.company().id;
        DataScope scope = new DataScope(
            actor.userId(),
            "department".equals(access.scope()) ? access.departmentId() : null,
            COMPANY_WIDE_SCOPES.contains(access.scope())
        );

        PayrollRunRef payrollRun = repository.payrollRun(selectedCompanyId, period.toString()).orElse(null);
        CostAggregate aggregate = payrollRun == null
            ? repository.estimatedCost(selectedCompanyId, scope)
            : repository.payrollCost(selectedCompanyId, payrollRun.id(), scope);
        List<DepartmentAggregate> departmentAggregates = payrollRun == null
            ? repository.estimatedDepartments(selectedCompanyId, scope)
            : repository.payrollDepartments(selectedCompanyId, payrollRun.id(), scope);
        var headcount = repository.headcount(selectedCompanyId, period.atDay(1), period.atEndOfMonth(), scope);
        BigDecimal operatingExpense = money(repository.operatingExpense(
            selectedCompanyId, period.atDay(1), period.atEndOfMonth(), scope
        ));
        CostSummary summary = costSummary(aggregate, operatingExpense);
        List<DepartmentCost> departments = departmentAggregates.stream()
            .map(department -> departmentCost(department, summary.total()))
            .sorted(Comparator.comparing(DepartmentCost::total).reversed())
            .toList();
        List<TrendPoint> trend = trend(selectedCompanyId, period, scope, aggregate, payrollRun);
        Headcount viewHeadcount = new Headcount(
            headcount.active(),
            headcount.probation(),
            headcount.onboarding(),
            headcount.departedThisMonth(),
            aggregate.employeeCount()
        );

        return new WorkforceCostView(
            selectedCompanyId,
            access.company().name,
            period.toString(),
            payrollRun == null ? "employee_estimate" : "payroll_run",
            payrollRun == null ? null : payrollRun.id(),
            payrollRun == null ? null : payrollRun.status(),
            viewHeadcount,
            summary,
            departments,
            trend,
            attentionItems(payrollRun, viewHeadcount, departments)
        );
    }

    private void requireReadPermission(AccessContextView access) {
        if (!access.permissions().contains("workforce.cost.read")
            && !access.permissions().contains("workforce.cost.manage")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workforce cost read permission required");
        }
    }

    private YearMonth parsePeriod(String value) {
        if (value == null || value.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Period must use YYYY-MM", error);
        }
    }

    private CostSummary costSummary(CostAggregate aggregate, BigDecimal operatingExpense) {
        BigDecimal total = money(aggregate.total());
        BigDecimal other = otherCost(aggregate);
        return new CostSummary(
            money(aggregate.salary()),
            money(aggregate.overtime()),
            money(aggregate.employerSocial()),
            money(aggregate.employerHousing()),
            other,
            total,
            average(total, aggregate.employeeCount()),
            operatingExpense,
            ratio(total, operatingExpense)
        );
    }

    private DepartmentCost departmentCost(DepartmentAggregate department, BigDecimal companyTotal) {
        CostAggregate cost = department.cost();
        BigDecimal total = money(cost.total());
        BigDecimal budget = money(department.budget());
        return new DepartmentCost(
            department.departmentId(),
            department.departmentName(),
            cost.employeeCount(),
            money(cost.salary()),
            money(cost.overtime()),
            money(cost.employerSocial()),
            money(cost.employerHousing()),
            otherCost(cost),
            total,
            average(total, cost.employeeCount()),
            ratio(total, companyTotal),
            budget,
            money(total.subtract(budget)),
            ratio(total, budget)
        );
    }

    private List<TrendPoint> trend(
        long companyId,
        YearMonth period,
        DataScope scope,
        CostAggregate selected,
        PayrollRunRef payrollRun
    ) {
        List<TrendPoint> points = new ArrayList<>(repository.payrollTrend(companyId, period.toString(), scope).stream()
            .map(point -> new TrendPoint(
                point.period(),
                money(point.total()),
                point.employeeCount(),
                average(point.total(), point.employeeCount()),
                point.status()
            ))
            .toList());
        boolean selectedPresent = points.stream().anyMatch(point -> period.toString().equals(point.period()));
        if (!selectedPresent && payrollRun == null) {
            points.add(new TrendPoint(
                period.toString(),
                money(selected.total()),
                selected.employeeCount(),
                average(selected.total(), selected.employeeCount()),
                "estimate"
            ));
        }
        points.sort(Comparator.comparing(TrendPoint::period));
        return points.size() <= 6 ? List.copyOf(points) : List.copyOf(points.subList(points.size() - 6, points.size()));
    }

    private List<AttentionItem> attentionItems(
        PayrollRunRef payrollRun,
        Headcount headcount,
        List<DepartmentCost> departments
    ) {
        List<AttentionItem> items = new ArrayList<>();
        if (payrollRun == null) {
            items.add(new AttentionItem(
                "payroll-not-created",
                "本月薪酬尚未生成月结批次",
                "当前展示员工档案中的实时估算；生成批次后将切换为不可漂移的薪酬快照。",
                "warning",
                "/admin/compensation"
            ));
        } else if (!"closed".equals(payrollRun.status())) {
            items.add(new AttentionItem(
                "payroll-not-closed",
                "本月薪酬批次待锁定",
                "当前批次仍可调整，锁定后可作为正式人力成本口径。",
                "warning",
                "/admin/compensation"
            ));
        }
        long overBudgetCount = departments.stream()
            .filter(department -> department.budget().signum() > 0 && department.budgetVariance().signum() > 0)
            .count();
        if (overBudgetCount > 0) {
            items.add(new AttentionItem(
                "department-budget-overrun",
                overBudgetCount + " 个部门人力成本超过部门预算",
                "建议复核部门编制、薪酬变动和预算口径。",
                "critical",
                "/hr/organization"
            ));
        }
        if (headcount.onboarding() > 0) {
            items.add(new AttentionItem(
                "pending-onboarding",
                headcount.onboarding() + " 人待入职",
                "待入职人员尚未计入本期人力成本，请确认预计到岗日期和预算。",
                "info",
                "/hr/organization"
            ));
        }
        if (headcount.departedThisMonth() > 0) {
            items.add(new AttentionItem(
                "departures-this-month",
                "本月有 " + headcount.departedThisMonth() + " 人离职",
                "请核对离职薪酬、社保停缴和工作交接状态。",
                "info",
                "/hr/organization"
            ));
        }
        return List.copyOf(items);
    }

    private BigDecimal otherCost(CostAggregate aggregate) {
        BigDecimal known = value(aggregate.salary())
            .add(value(aggregate.overtime()))
            .add(value(aggregate.employerSocial()))
            .add(value(aggregate.employerHousing()));
        return money(value(aggregate.total()).subtract(known).max(BigDecimal.ZERO));
    }

    private BigDecimal average(BigDecimal total, int count) {
        return count <= 0 ? BigDecimal.ZERO : money(value(total).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP));
    }

    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return value(denominator).signum() == 0
            ? BigDecimal.ZERO.setScale(4)
            : value(numerator).divide(value(denominator), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return value(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
