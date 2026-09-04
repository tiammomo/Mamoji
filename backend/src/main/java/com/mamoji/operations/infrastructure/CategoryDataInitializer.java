package com.mamoji.operations.infrastructure;

import com.mamoji.platform.tenant.Company;
import com.mamoji.operations.application.CategoryRepository;
import com.mamoji.operations.domain.Category;
import com.mamoji.platform.tenant.CompanyRepository;
import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** Initializes durable company-scoped categories after owner memberships exist. */
@Component
@DependsOn("ledgerDataInitializer")
public class CategoryDataInitializer {
    private static final List<CategorySeed> DEMO_CATEGORIES = List.of(
        new CategorySeed("主营业务收入", "💼", "#22c55e", "income"),
        new CategorySeed("团队餐饮", "🍜", "#f97316", "expense"),
        new CategorySeed("差旅交通", "🚇", "#0ea5e9", "expense"),
        new CategorySeed("办公采购", "🛍️", "#a855f7", "expense"),
        new CategorySeed("客户退款", "↩", "#f43f5e", "expense"),
        new CategorySeed("离职补偿", "HR", "#8b5cf6", "expense"),
        new CategorySeed("办公租赁", "🏢", "#6366f1", "expense"),
        new CategorySeed("税费", "🧾", "#ef4444", "expense")
    );

    private final CategoryRepository categories;
    private final CompanyRepository companies;
    private final String bootstrapMode;

    public CategoryDataInitializer(
        CategoryRepository categories,
        CompanyRepository companies,
        @Value("${mamoji.bootstrap.mode:demo}") String bootstrapMode
    ) {
        this.categories = categories;
        this.companies = companies;
        this.bootstrapMode = bootstrapMode == null ? "demo" : bootstrapMode.trim().toLowerCase(Locale.ROOT);
    }

    @PostConstruct
    void initialize() {
        if (!"bootstrap".equals(bootstrapMode)) {
            companies.findAll().stream()
                .filter(company -> "company".equals(company.entityType))
                .min(Comparator.comparingLong(company -> company.id))
                .ifPresent(this::ensureDemoCategories);
        }
        companies.findAll().forEach(company ->
            categories.ensureCompanyDefaults(company.ownerId, company.id));
    }

    private void ensureDemoCategories(Company company) {
        if (!categories.findAll(company.ownerId, company.id, null).isEmpty()) {
            return;
        }
        DEMO_CATEGORIES.stream()
            .map(seed -> category(company, seed))
            .forEach(categories::insert);
    }

    private Category category(Company company, CategorySeed seed) {
        String now = OffsetDateTime.now().toString();
        Category category = new Category();
        category.companyId = company.id;
        category.userId = company.ownerId;
        category.name = seed.name();
        category.icon = seed.icon();
        category.color = seed.color();
        category.type = seed.type();
        category.status = 1;
        category.createdAt = now;
        category.updatedAt = now;
        return category;
    }

    private record CategorySeed(String name, String icon, String color, String type) {}
}
