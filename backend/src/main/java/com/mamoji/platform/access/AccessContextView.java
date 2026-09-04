package com.mamoji.platform.access;

import com.mamoji.platform.tenant.Company;
import com.mamoji.platform.identity.User;
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
