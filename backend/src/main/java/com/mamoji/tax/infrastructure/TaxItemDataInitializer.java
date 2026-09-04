package com.mamoji.tax.infrastructure;

import com.mamoji.platform.tenant.Company;
import com.mamoji.platform.tenant.CompanyRepository;
import com.mamoji.tax.application.TaxItemRepository;
import com.mamoji.tax.domain.TaxItem;
import com.mamoji.tax.domain.TaxItemPolicy;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Seeds tax demo data once, after company policy data is durable. */
@Component
@DependsOn("enterpriseDataInitializer")
public class TaxItemDataInitializer {
    private static final List<TaxSeed> DEMO_ITEMS = List.of(
        new TaxSeed(
            "2026-Q2 增值税申报", "2026-Q2", "vat", "17800", "0", "0", "2026-07-15", "estimated",
            "代理记账/财务负责人", "深圳小规模创业团队季度零税款申报口径"
        ),
        new TaxSeed(
            "2026-Q2 企业所得税预缴", "2026-Q2", "corporate_income_tax", "45200", "2260", "0",
            "2026-07-15", "pending", "财务负责人", "按季度利润估算"
        ),
        new TaxSeed(
            "2026-06 个税代扣代缴", "2026-06", "personal_income_tax", "74000", "6800", "1200",
            "2026-07-15", "pending", "财务负责人", "按当前员工薪资样例估算"
        ),
        new TaxSeed(
            "2026-Q2 附加税费确认", "2026-Q2", "surcharge", "0", "0", "0", "2026-07-15", "estimated",
            "代理记账/财务负责人", "增值税为零时附加税费同步为零"
        )
    );

    private final TaxItemRepository taxItems;
    private final TaxItemPolicy policy;
    private final CompanyRepository companies;
    private final TransactionTemplate transaction;
    private final String bootstrapMode;

    public TaxItemDataInitializer(
        TaxItemRepository taxItems,
        TaxItemPolicy policy,
        CompanyRepository companies,
        PlatformTransactionManager transactionManager,
        @Value("${mamoji.bootstrap.mode:demo}") String bootstrapMode
    ) {
        this.taxItems = taxItems;
        this.policy = policy;
        this.companies = companies;
        this.transaction = new TransactionTemplate(transactionManager);
        this.bootstrapMode = bootstrapMode == null ? "demo" : bootstrapMode.trim().toLowerCase(Locale.ROOT);
    }

    @PostConstruct
    void initialize() {
        if ("bootstrap".equals(bootstrapMode)) {
            return;
        }
        transaction.executeWithoutResult(ignored -> companies.findAll().stream()
            .filter(company -> "company".equals(company.entityType))
            .min(Comparator.comparingLong(company -> company.id))
            .ifPresent(this::seedDemoItems));
    }

    private void seedDemoItems(Company company) {
        if (!taxItems.findByCompany(company.id).isEmpty() || taxItems.hasLifecycleHistory(company.id)) {
            return;
        }
        DEMO_ITEMS.stream().map(seed -> item(company, seed)).forEach(taxItems::insert);
    }

    private TaxItem item(Company company, TaxSeed seed) {
        String now = OffsetDateTime.now().toString();
        TaxItem item = new TaxItem();
        item.companyId = company.id;
        item.name = seed.name();
        item.period = seed.period();
        item.taxType = seed.taxType();
        item.taxableAmount = new BigDecimal(seed.taxableAmount());
        item.taxAmount = new BigDecimal(seed.taxAmount());
        item.paidAmount = new BigDecimal(seed.paidAmount());
        item.deductibleAmount = BigDecimal.ZERO;
        item.taxRate = null;
        item.dueDate = seed.dueDate();
        item.status = seed.status();
        item.filingStatus = policy.filingStatus(item.status);
        item.paymentStatus = null;
        item.frequency = policy.frequency(item.period);
        item.responsiblePerson = seed.responsiblePerson();
        item.policyBasis = company.policyProfileKey;
        item.sourceType = "demo_estimate";
        item.note = seed.note();
        item.createdAt = now;
        item.updatedAt = now;
        return policy.apply(item, true, false, company.policyProfileKey, LocalDate.now());
    }

    private record TaxSeed(
        String name,
        String period,
        String taxType,
        String taxableAmount,
        String taxAmount,
        String paidAmount,
        String dueDate,
        String status,
        String responsiblePerson,
        String note
    ) {}
}
