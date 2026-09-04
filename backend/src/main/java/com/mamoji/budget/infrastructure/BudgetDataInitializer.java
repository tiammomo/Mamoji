package com.mamoji.budget.infrastructure;

import com.mamoji.budget.application.BudgetRepository;
import com.mamoji.budget.domain.Budget;
import com.mamoji.budget.domain.BudgetPolicy;
import com.mamoji.platform.tenant.Company;
import com.mamoji.platform.tenant.CompanyRepository;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** Owns optional budget demo data after enterprise subjects and accounting scopes exist. */
@Component
@ConditionalOnProperty(name = "mamoji.bootstrap.mode", havingValue = "demo", matchIfMissing = true)
@DependsOn("enterpriseDataInitializer")
public class BudgetDataInitializer {
    private final BudgetRepository budgets;
    private final BudgetPolicy policy;
    private final CompanyRepository companies;

    public BudgetDataInitializer(
        BudgetRepository budgets,
        BudgetPolicy policy,
        CompanyRepository companies
    ) {
        this.budgets = budgets;
        this.policy = policy;
        this.companies = companies;
    }

    @PostConstruct
    void initialize() {
        Optional<Company> company = companies.findAll().stream()
            .filter(candidate -> "company".equals(candidate.entityType))
            .min(Comparator.comparing(candidate -> candidate.id));
        if (company.isEmpty() || !budgets.findByCompany(company.get().id).isEmpty()) {
            return;
        }

        Company subject = company.get();
        LocalDate today = LocalDate.now();
        String now = OffsetDateTime.now().toString();
        Budget budget = new Budget();
        budget.companyId = subject.id;
        budget.userId = subject.ownerId;
        budget.name = "本月经营预算";
        budget.amount = new BigDecimal("6000.00");
        budget.startDate = today.withDayOfMonth(1).toString();
        budget.endDate = today.withDayOfMonth(today.lengthOfMonth()).toString();
        budget.warningThreshold = 85;
        budget.status = 1;
        budget.spent = BigDecimal.ZERO;
        budget.reservedAmount = BigDecimal.ZERO;
        budget.createdAt = now;
        budget.updatedAt = now;
        budgets.insert(policy.apply(budget));

        Budget projected = budgets.findById(subject.id, budget.id).orElseThrow();
        projected.updatedAt = OffsetDateTime.now().toString();
        budgets.persistProjection(projected);
    }
}
