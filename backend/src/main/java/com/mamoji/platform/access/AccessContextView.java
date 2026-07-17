package com.mamoji.platform.access;

import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.User;
import com.mamoji.platform.product.ProductModuleCatalog.ProductModules;
import java.util.List;
import java.util.Set;

/** Stable frontend contract for identity, tenant and capability-aware UI. */
public record AccessContextView(
    User actor,
    Company company,
    List<Company> companies,
    String role,
    String scope,
    Long departmentId,
    Set<String> permissions,
    ProductModules modules
) {
}
