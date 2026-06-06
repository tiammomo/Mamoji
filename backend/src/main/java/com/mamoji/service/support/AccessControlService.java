package com.mamoji.service.support;

import com.mamoji.common.Permissions;
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
        Optional<Employee> employee = enterpriseStore.employees.values().stream()
            .filter(candidate -> Objects.equals(candidate.userId, user.id))
            .findFirst();
        boolean peopleRole = employee
            .map(candidate -> candidate.accessRole.equals("founder") || candidate.accessRole.equals("hr_admin"))
            .orElse(false);
        if (user.role == Roles.ADMIN || peopleRole || (user.permissions & Permissions.USER) != 0) {
            return user;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "People management permission required");
    }

    public User requireFinanceManager(String authorization) {
        User user = requireUser(authorization);
        Optional<Employee> employee = enterpriseStore.employees.values().stream()
            .filter(candidate -> Objects.equals(candidate.userId, user.id))
            .findFirst();
        boolean financeRole = employee
            .map(candidate -> candidate.accessRole.equals("founder") || candidate.accessRole.equals("finance_admin"))
            .orElse(false);
        if (user.role == Roles.ADMIN || financeRole || (user.permissions & Permissions.ACCOUNT) != 0) {
            return user;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Finance management permission required");
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
            .map(employee -> employee.companyId)
            .forEach(employeeCompanyIds::add);
        return enterpriseStore.sortedCompanies().stream()
            .filter(company -> company.ownerId == user.id || employeeCompanyIds.contains(company.id))
            .toList();
    }

    public boolean canAccessCompany(User user, long companyId) {
        return user.role == Roles.ADMIN || accessibleCompanies(user).stream().anyMatch(company -> company.id == companyId);
    }

    private Company defaultCompany(User user) {
        List<Company> companies = accessibleCompanies(user);
        if (!companies.isEmpty()) {
            return companies.get(0);
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
