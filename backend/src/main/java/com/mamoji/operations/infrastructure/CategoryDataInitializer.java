package com.mamoji.operations.infrastructure;

import com.mamoji.operations.application.CategoryRepository;
import com.mamoji.platform.tenant.CompanyRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** Ensures generic company categories only in explicit production bootstrap mode. */
@Component
@ConditionalOnProperty(name = "mamoji.bootstrap.mode", havingValue = "bootstrap")
@DependsOn("ledgerDataInitializer")
public class CategoryDataInitializer {
    private final CategoryRepository categories;
    private final CompanyRepository companies;

    public CategoryDataInitializer(CategoryRepository categories, CompanyRepository companies) {
        this.categories = categories;
        this.companies = companies;
    }

    @PostConstruct
    void initialize() {
        companies.findAll().forEach(company ->
            categories.ensureCompanyDefaults(company.ownerId, company.id));
    }
}
