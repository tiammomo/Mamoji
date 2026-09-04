package com.mamoji.operations.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mamoji.domain.Models.Company;
import com.mamoji.operations.application.CategoryRepository;
import com.mamoji.operations.domain.Category;
import com.mamoji.repository.EnterpriseStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CategoryDataInitializerTest {
    @Test
    void demoModeSeedsDetailedCategoriesBeforeEnsuringEveryCompanyDefault() {
        CategoryRepository categories = org.mockito.Mockito.mock(CategoryRepository.class);
        EnterpriseStore enterpriseStore = org.mockito.Mockito.mock(EnterpriseStore.class);
        Company first = company(11, 101, "company");
        Company household = company(12, 101, "household");
        when(enterpriseStore.sortedCompanies()).thenReturn(List.of(first, household));
        when(categories.findAll(101, 11, null)).thenReturn(List.of());
        CategoryDataInitializer initializer = new CategoryDataInitializer(categories, enterpriseStore, "demo");

        initializer.initialize();

        ArgumentCaptor<Category> inserted = ArgumentCaptor.forClass(Category.class);
        verify(categories, org.mockito.Mockito.times(8)).insert(inserted.capture());
        assertEquals(
            List.of("主营业务收入", "团队餐饮", "差旅交通", "办公采购", "客户退款", "离职补偿", "办公租赁", "税费"),
            inserted.getAllValues().stream().map(category -> category.name).toList()
        );
        verify(categories).ensureCompanyDefaults(101, 11);
        verify(categories).ensureCompanyDefaults(101, 12);
    }

    @Test
    void demoModeDoesNotRestoreCategoriesAUserAlreadyCustomized() {
        CategoryRepository categories = org.mockito.Mockito.mock(CategoryRepository.class);
        EnterpriseStore enterpriseStore = org.mockito.Mockito.mock(EnterpriseStore.class);
        Company company = company(21, 201, "company");
        when(enterpriseStore.sortedCompanies()).thenReturn(List.of(company));
        when(categories.findAll(201, 21, null)).thenReturn(List.of(
            category("主营业务收入", "income"),
            category("团队餐饮", "expense")
        ));
        CategoryDataInitializer initializer = new CategoryDataInitializer(categories, enterpriseStore, "demo");

        initializer.initialize();

        verify(categories, never()).insert(any());
        verify(categories).ensureCompanyDefaults(201, 21);
    }

    @Test
    void bootstrapModeOnlyEnsuresGenericDefaults() {
        CategoryRepository categories = org.mockito.Mockito.mock(CategoryRepository.class);
        EnterpriseStore enterpriseStore = org.mockito.Mockito.mock(EnterpriseStore.class);
        Company company = company(31, 301, "company");
        when(enterpriseStore.sortedCompanies()).thenReturn(List.of(company));
        CategoryDataInitializer initializer = new CategoryDataInitializer(categories, enterpriseStore, "bootstrap");

        initializer.initialize();

        verify(categories, never()).findAll(anyLong(), anyLong(), any());
        verify(categories, never()).insert(any());
        verify(categories).ensureCompanyDefaults(301, 31);
    }

    private Company company(long id, long ownerId, String entityType) {
        Company company = new Company();
        company.id = id;
        company.ownerId = ownerId;
        company.entityType = entityType;
        return company;
    }

    private Category category(String name, String type) {
        Category category = new Category();
        category.name = name;
        category.type = type;
        return category;
    }
}
