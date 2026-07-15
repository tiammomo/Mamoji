package com.mamoji.service.support;

import com.mamoji.common.Roles;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.Employee;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccessControlService {
    private final InMemoryStore store;
    private final EnterpriseStore enterpriseStore;

    public AccessControlService(InMemoryStore store, EnterpriseStore enterpriseStore) {
        this.store = store;
        this.enterpriseStore = enterpriseStore;
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
        boolean peopleRole = enterpriseStore.employees.values().stream()
            .filter(candidate -> Objects.equals(candidate.userId, user.id))
            .filter(this::hasActiveCompanyAccess)
            .filter(candidate -> hasCompanyWideWriteScope(candidate.accessScope))
            .anyMatch(candidate -> candidate.accessRole.equals("founder") || candidate.accessRole.equals("hr_admin"));
        if (user.role == Roles.ADMIN || peopleRole) {
            return user;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "People management permission required");
    }

    public User requireFinanceManager(String authorization) {
        User user = requireUser(authorization);
        boolean financeRole = enterpriseStore.employees.values().stream()
            .filter(candidate -> Objects.equals(candidate.userId, user.id))
            .filter(this::hasActiveCompanyAccess)
            .filter(candidate -> hasCompanyWideWriteScope(candidate.accessScope))
            .anyMatch(candidate -> candidate.accessRole.equals("founder") || candidate.accessRole.equals("finance_admin"));
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
        return employeeForUser(user, companyId)
            .filter(employee -> Set.of("founder", "hr_admin", "finance_admin", "department_manager", "viewer").contains(employee.accessRole))
            .filter(employee -> hasCompanyWideReadScope(employee.accessScope))
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
        return employeeForUser(user, companyId)
            .filter(employee -> roleSet.contains(employee.accessRole))
            .filter(employee -> hasCompanyWideWriteScope(employee.accessScope))
            .isPresent();
    }

    public boolean hasCompanyRole(User user, long companyId, String... roles) {
        if (user.role == Roles.ADMIN) {
            return true;
        }
        Set<String> roleSet = Set.of(roles);
        Company company = enterpriseStore.companies.get(companyId);
        if (company != null && company.ownerId == user.id && roleSet.contains("founder")) {
            return true;
        }
        return employeeForUser(user, companyId)
            .map(employee -> roleSet.contains(employee.accessRole))
            .orElse(false);
    }

    private boolean isGlobalAdminOrOwner(User user, long companyId) {
        return user.role == Roles.ADMIN || isCompanyOwner(user, companyId);
    }

    private boolean isCompanyOwner(User user, long companyId) {
        Company company = enterpriseStore.companies.get(companyId);
        return company != null && company.ownerId == user.id;
    }

    private boolean hasCompanyWideWriteScope(String accessScope) {
        return "group".equals(accessScope) || "company".equals(accessScope) || "company_set".equals(accessScope);
    }

    private boolean hasCompanyWideReadScope(String accessScope) {
        return hasCompanyWideWriteScope(accessScope) || "readonly".equals(accessScope);
    }

    public Optional<Employee> employeeForUser(User user, long companyId) {
        return enterpriseStore.employees.values().stream()
            .filter(employee -> employee.companyId == companyId)
            .filter(employee -> Objects.equals(employee.userId, user.id))
            .filter(this::hasActiveCompanyAccess)
            .min(Comparator.comparingLong(employee -> employee.id));
    }

    public Company resolveCompany(User user, Long companyId) {
        if (companyId == null || companyId == 0) {
            return defaultCompany(user);
        }
        Company company = require(enterpriseStore.companies.get(companyId), "Company not found");
        if (!canAccessCompany(user, company.id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to company");
        }
        return company;
    }

    public List<Company> accessibleCompanies(User user) {
        if (user.role == Roles.ADMIN) {
            return enterpriseStore.sortedCompanies();
        }
        Set<Long> employeeCompanyIds = new HashSet<>();
        enterpriseStore.employees.values().stream()
            .filter(employee -> Objects.equals(employee.userId, user.id))
            .filter(this::hasActiveCompanyAccess)
            .map(employee -> employee.companyId)
            .forEach(employeeCompanyIds::add);
        return enterpriseStore.sortedCompanies().stream()
            .filter(company -> company.ownerId == user.id || employeeCompanyIds.contains(company.id))
            .toList();
    }

    public boolean canAccessCompany(User user, long companyId) {
        return user.role == Roles.ADMIN || accessibleCompanies(user).stream().anyMatch(company -> company.id == companyId);
    }

    private boolean hasActiveCompanyAccess(Employee employee) {
        return employee.status != null && !"departed".equals(employee.status);
    }

    private Company defaultCompany(User user) {
        List<Company> companies = accessibleCompanies(user);
        if (!companies.isEmpty()) {
            return companies.get(0);
        }
        if (user.role != Roles.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No company access");
        }
        return enterpriseStore.sortedCompanies().stream()
            .min(Comparator.comparing(company -> company.id))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));
    }

    private <T> T require(T value, String message) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        }
        return value;
    }
}
