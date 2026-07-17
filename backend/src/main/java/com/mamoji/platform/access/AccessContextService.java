package com.mamoji.platform.access;

import com.mamoji.common.Roles;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.User;
import com.mamoji.platform.product.ProductModuleCatalog;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.tenant.CompanyMembership;
import com.mamoji.service.support.AccessControlService;
import com.mamoji.service.support.EnterprisePermissionCatalog;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccessContextService {
    private final AccessControlService accessControl;
    private final EnterprisePermissionCatalog permissionCatalog;
    private final ProductModuleCatalog productModules;

    public AccessContextService(
        AccessControlService accessControl,
        EnterprisePermissionCatalog permissionCatalog,
        ProductModuleCatalog productModules
    ) {
        this.accessControl = accessControl;
        this.permissionCatalog = permissionCatalog;
        this.productModules = productModules;
    }

    public AccessContextView resolve(String authorization, Long requestedCompanyId) {
        return resolve(accessControl.requireUser(authorization), requestedCompanyId);
    }

    public AccessContextView resolve(ActorContext actor, Long requestedCompanyId) {
        return resolve(actor.user(), requestedCompanyId);
    }

    public AccessContextView resolve(User user, Long requestedCompanyId) {
        List<Company> companies = accessControl.accessibleCompanies(user).stream()
            .filter(company -> productModules.householdEnabled() || !"household".equals(company.entityType))
            .toList();
        Company company = resolveCompany(companies, requestedCompanyId);
        CompanyMembership membership = accessControl.membershipForUser(user, company.id).orElse(null);
        String role = membership == null
            ? (user.role == Roles.ADMIN || company.ownerId == user.id ? "founder" : "viewer")
            : membership.role();
        String scope = membership == null
            ? (user.role == Roles.ADMIN || company.ownerId == user.id ? "company" : "readonly")
            : membership.scope();
        Set<String> permissions = new LinkedHashSet<>(permissionCatalog.permissionsForRole(role));
        if (user.role == Roles.ADMIN) {
            permissions.addAll(permissionCatalog.allPermissionKeys());
        }
        return new AccessContextView(
            user,
            company,
            companies,
            role,
            scope,
            membership == null ? null : membership.departmentId(),
            Set.copyOf(permissions),
            productModules.snapshot()
        );
    }

    public Company requireCompany(ActorContext actor, Long requestedCompanyId) {
        return resolve(actor, requestedCompanyId).company();
    }

    public void requirePermission(ActorContext actor, long companyId, String permission) {
        AccessContextView context = resolve(actor, companyId);
        if (!context.permissions().contains(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Permission required: " + permission);
        }
    }

    private Company resolveCompany(List<Company> companies, Long requestedCompanyId) {
        if (companies.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No enabled company access");
        }
        if (requestedCompanyId == null || requestedCompanyId <= 0) {
            return companies.get(0);
        }
        return companies.stream()
            .filter(company -> company.id == requestedCompanyId)
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to selected company"));
    }
}
