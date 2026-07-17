package com.mamoji.service.support;

import com.mamoji.common.Roles;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.Employee;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.platform.tenant.CompanyMembership;
import com.mamoji.platform.tenant.CompanyMembershipRepository;
import com.mamoji.platform.product.ProductModuleCatalog;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccessControlService {
    private final InMemoryStore store;
    private final EnterpriseStore enterpriseStore;
    private final CompanyMembershipRepository memberships;
    private final ProductModuleCatalog productModules;

    public AccessControlService(
        InMemoryStore store,
        EnterpriseStore enterpriseStore,
        CompanyMembershipRepository memberships,
        ProductModuleCatalog productModules
    ) {
        this.store = store;
        this.enterpriseStore = enterpriseStore;
        this.memberships = memberships;
        this.productModules = productModules;
    }

    public User requireUser(String authorization) {
        return store.currentUser(authorization)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized"));
    }

    public User requireAdmin(String authorization) {
        User user = requireUser(authorization);
        if (user.role != Roles.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
        return user;
    }

    public User requirePeopleManager(String authorization) {
        User user = requireUser(authorization);
        boolean peopleRole = memberships.findActiveByUser(user.id).stream()
            .filter(candidate -> hasCompanyWideWriteScope(candidate.scope()))
            .anyMatch(candidate -> candidate.role().equals("founder") || candidate.role().equals("hr_admin"));
        if (user.role == Roles.ADMIN || peopleRole) {
            return user;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "People management permission required");
    }

    public User requireFinanceManager(String authorization) {
        User user = requireUser(authorization);
        boolean financeRole = memberships.findActiveByUser(user.id).stream()
            .filter(candidate -> hasCompanyWideWriteScope(candidate.scope()))
            .anyMatch(candidate -> candidate.role().equals("founder") || candidate.role().equals("finance_admin"));
        if (user.role == Roles.ADMIN || financeRole) {
            return user;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Finance management permission required");
    }

    public void requirePeopleManager(User user, long companyId) {
        if (!hasPeopleManagerRole(user, companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "People management permission required");
        }
    }

    public void requireFinanceManager(User user, long companyId) {
        if (!hasFinanceManagerRole(user, companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Finance management permission required");
        }
    }

    public void requirePayrollManager(User user, long companyId) {
        if (!hasPayrollManagerRole(user, companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Payroll management permission required");
        }
    }

    public boolean hasPeopleManagerRole(User user, long companyId) {
        return hasCompanyManagementRole(user, companyId, "founder", "hr_admin");
    }

    public boolean hasFinanceManagerRole(User user, long companyId) {
        return hasCompanyManagementRole(user, companyId, "founder", "finance_admin");
    }

    public boolean hasPayrollManagerRole(User user, long companyId) {
        return hasCompanyManagementRole(user, companyId, "founder", "hr_admin", "finance_admin");
    }

    public boolean canReadPeopleDirectory(User user, long companyId) {
        if (isGlobalAdminOrOwner(user, companyId)) {
            return true;
        }
        return membershipForUser(user, companyId)
            .filter(membership -> Set.of("founder", "hr_admin", "finance_admin", "department_manager", "viewer").contains(membership.role()))
            .filter(membership -> hasCompanyWideReadScope(membership.scope()))
            .isPresent();
    }

    public boolean hasCompanyManagementRole(User user, long companyId, String... roles) {
        if (user.role == Roles.ADMIN) {
            return true;
        }
        if (isCompanyOwner(user, companyId) && Set.of(roles).contains("founder")) {
            return true;
        }
        Set<String> roleSet = Set.of(roles);
        return membershipForUser(user, companyId)
            .filter(membership -> roleSet.contains(membership.role()))
            .filter(membership -> hasCompanyWideWriteScope(membership.scope()))
            .isPresent();
    }

    public boolean hasCompanyRole(User user, long companyId, String... roles) {
        if (user.role == Roles.ADMIN) {
            return true;
        }
        Set<String> roleSet = Set.of(roles);
        Company company = enterpriseStore.findCompany(companyId).orElse(null);
        if (company != null && company.ownerId == user.id && roleSet.contains("founder")) {
            return true;
        }
        return membershipForUser(user, companyId)
            .map(membership -> roleSet.contains(membership.role()))
            .orElse(false);
    }

    private boolean isGlobalAdminOrOwner(User user, long companyId) {
        return user.role == Roles.ADMIN || isCompanyOwner(user, companyId);
    }

    private boolean isCompanyOwner(User user, long companyId) {
        Company company = enterpriseStore.findCompany(companyId).orElse(null);
        return company != null && company.ownerId == user.id;
    }

    private boolean hasCompanyWideWriteScope(String accessScope) {
        return "group".equals(accessScope) || "company".equals(accessScope) || "company_set".equals(accessScope);
    }

    private boolean hasCompanyWideReadScope(String accessScope) {
        return hasCompanyWideWriteScope(accessScope) || "readonly".equals(accessScope);
    }

    public Optional<Employee> employeeForUser(User user, long companyId) {
        return enterpriseStore.findActiveEmployeeByUser(user.id, companyId);
    }

    public Optional<CompanyMembership> membershipForUser(User user, long companyId) {
        return memberships.find(user.id, companyId).filter(CompanyMembership::active);
    }

    public Company resolveCompany(User user, Long companyId) {
        if (companyId == null || companyId == 0) {
            return defaultCompany(user);
        }
        Company company = enterpriseStore.findCompany(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));
        if (!canAccessCompany(user, company.id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to company");
        }
        return company;
    }

    public List<Company> accessibleCompanies(User user) {
        if (user.role == Roles.ADMIN) {
            return enterpriseStore.sortedCompanies().stream()
                .filter(company -> productModules.householdEnabled() || !"household".equals(company.entityType))
                .toList();
        }
        Set<Long> employeeCompanyIds = new HashSet<>();
        memberships.findActiveByUser(user.id).stream()
            .map(CompanyMembership::companyId)
            .forEach(employeeCompanyIds::add);
        return enterpriseStore.sortedCompanies().stream()
            .filter(company -> productModules.householdEnabled() || !"household".equals(company.entityType))
            .filter(company -> company.ownerId == user.id || employeeCompanyIds.contains(company.id))
            .toList();
    }

    public boolean canAccessCompany(User user, long companyId) {
        return accessibleCompanies(user).stream().anyMatch(company -> company.id == companyId);
    }

    private Company defaultCompany(User user) {
        List<Company> companies = accessibleCompanies(user);
        if (!companies.isEmpty()) {
            return companies.get(0);
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No enabled company access");
    }

    private <T> T require(T value, String message) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        }
        return value;
    }
}
