package com.mamoji.service;

import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.Department;
import com.mamoji.domain.Models.Employee;
import com.mamoji.domain.Models.EntityTransfer;
import com.mamoji.domain.Models.EmploymentEvent;
import com.mamoji.domain.Models.TaxItem;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.service.support.AccessControlService;
import com.mamoji.service.support.EnterprisePermissionCatalog;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.intValue;
import static com.mamoji.common.PayloadReader.longParam;
import static com.mamoji.common.PayloadReader.nullableText;
import static com.mamoji.common.PayloadReader.number;
import static com.mamoji.common.PayloadReader.optionalLong;
import static com.mamoji.common.PayloadReader.text;
import static com.mamoji.common.PayloadReader.textOr;
import static com.mamoji.service.support.DomainSupport.require;
import static com.mamoji.service.support.DomainSupport.touch;

@Service
public class EnterpriseManagementService {
    private final EnterpriseStore enterpriseStore;
    private final AccessControlService accessControl;
    private final EnterprisePermissionCatalog permissionCatalog;

    public EnterpriseManagementService(
        EnterpriseStore enterpriseStore,
        AccessControlService accessControl,
        EnterprisePermissionCatalog permissionCatalog
    ) {
        this.enterpriseStore = enterpriseStore;
        this.accessControl = accessControl;
        this.permissionCatalog = permissionCatalog;
    }

    public Map<String, Object> summary(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        List<Employee> employees = enterpriseStore.sortedEmployees(company.id);
        List<TaxItem> taxes = enterpriseStore.sortedTaxItems(company.id);
        BigDecimal monthlyPeopleCost = employees.stream()
            .filter(employee -> !employee.status.equals("departed"))
            .map(employee -> employee.monthlyCost)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pendingTaxAmount = taxes.stream()
            .filter(item -> !item.status.equals("paid"))
            .map(item -> item.taxAmount.subtract(item.paidAmount))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        YearMonth current = YearMonth.now();
        long departuresThisMonth = employees.stream()
            .filter(employee -> employee.leaveDate != null && !employee.leaveDate.isBlank())
            .filter(employee -> sameMonth(employee.leaveDate, current))
            .count();
        long hiresThisMonth = employees.stream()
            .filter(employee -> sameMonth(employee.hireDate, current))
            .count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("company", company);
        result.put("departmentCount", enterpriseStore.sortedDepartments(company.id).size());
        result.put("employeeCount", employees.size());
        result.put("activeEmployeeCount", employees.stream().filter(employee -> employee.status.equals("active") || employee.status.equals("probation")).count());
        result.put("onboardingCount", employees.stream().filter(employee -> employee.status.equals("onboarding")).count());
        result.put("departedCount", employees.stream().filter(employee -> employee.status.equals("departed")).count());
        result.put("hiresThisMonth", hiresThisMonth);
        result.put("departuresThisMonth", departuresThisMonth);
        result.put("monthlyPeopleCost", monthlyPeopleCost);
        result.put("pendingTaxAmount", pendingTaxAmount);
        result.put("nextTaxDueDate", taxes.stream().filter(item -> !item.status.equals("paid")).map(item -> item.dueDate).min(String::compareTo).orElse(null));
        return result;
    }

    public Map<String, Object> permissionMatrix(String authorization) {
        accessControl.requireUser(authorization);
        return permissionCatalog.matrix();
    }

    public List<Company> listCompanies(String authorization) {
        return accessControl.accessibleCompanies(accessControl.requireUser(authorization));
    }

    public Company createCompany(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        Company company = enterpriseStore.company(
            user.id,
            textOr(body.get("name"), "新公司主体"),
            textOr(body.get("entityType"), "company"),
            nullableText(body.get("creditCode")),
            textOr(body.get("industry"), "未设置"),
            textOr(body.get("taxpayerType"), "未设置"),
            textOr(body.get("currency"), "CNY")
        );
        applyCompanyFields(company, body);
        touch(company);
        enterpriseStore.saveCompany(company);
        return company;
    }

    public Company companyProfile(String authorization, Long companyId) {
        return accessControl.resolveCompany(accessControl.requireUser(authorization), companyId);
    }

    public Company updateCompanyProfile(String authorization, Long companyId, Map<String, Object> body) {
        User user = accessControl.requirePeopleManager(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        applyCompanyFields(company, body);
        touch(company);
        enterpriseStore.saveCompany(company);
        return company;
    }

    public List<Department> listDepartments(String authorization, Long companyId) {
        Company company = accessControl.resolveCompany(accessControl.requireUser(authorization), companyId);
        return enterpriseStore.sortedDepartments(company.id);
    }

    public Department createDepartment(String authorization, Map<String, Object> body) {
        User user = accessControl.requirePeopleManager(authorization);
        Company company = accessControl.resolveCompany(user, optionalLong(body.get("companyId")).orElse(null));
        return enterpriseStore.department(
            company.id,
            textOr(body.get("name"), "新部门"),
            textOr(body.get("costCenter"), "GENERAL"),
            String.valueOf(number(body.get("budget"), BigDecimal.ZERO))
        );
    }

    public List<Employee> listEmployees(String authorization, Map<String, String> params) {
        Company company = accessControl.resolveCompany(accessControl.requireUser(authorization), optionalLong(params.get("companyId")).orElse(null));
        String keyword = params.getOrDefault("keyword", "").toLowerCase();
        String status = params.getOrDefault("status", "");
        long departmentId = longParam(params, "departmentId", 0);
        return enterpriseStore.sortedEmployees(company.id).stream()
            .filter(employee -> keyword.isBlank()
                || employee.name.toLowerCase().contains(keyword)
                || employee.email.toLowerCase().contains(keyword)
                || employee.position.toLowerCase().contains(keyword)
                || (employee.departmentName != null && employee.departmentName.toLowerCase().contains(keyword)))
            .filter(employee -> status.isBlank() || employee.status.equals(status))
            .filter(employee -> departmentId == 0 || (employee.departmentId != null && employee.departmentId == departmentId))
            .toList();
    }

    public Employee createEmployee(String authorization, Map<String, Object> body) {
        User operator = accessControl.requirePeopleManager(authorization);
        Company company = accessControl.resolveCompany(operator, optionalLong(body.get("companyId")).orElse(null));
        Employee employee = enterpriseStore.employee(
            company.id,
            optionalLong(body.get("userId")).orElse(null),
            optionalLong(body.get("departmentId")).orElse(null),
            textOr(body.get("name"), "新员工"),
            textOr(body.get("email"), "employee-" + System.currentTimeMillis() + "@mamoji.local"),
            nullableText(body.get("phone")),
            textOr(body.get("position"), "团队成员"),
            textOr(body.get("employmentType"), "full_time"),
            textOr(body.get("status"), "onboarding"),
            textOr(body.get("accessRole"), "employee"),
            textOr(body.get("accessScope"), "self"),
            textOr(body.get("hireDate"), LocalDate.now().toString()),
            nullableText(body.get("leaveDate")),
            String.valueOf(number(body.get("salary"), BigDecimal.ZERO)),
            String.valueOf(number(body.get("socialInsurance"), BigDecimal.ZERO)),
            String.valueOf(number(body.get("housingFund"), BigDecimal.ZERO)),
            String.valueOf(number(body.get("taxEstimate"), BigDecimal.ZERO)),
            null,
            nullableText(body.get("emergencyContact"))
        );
        enterpriseStore.event(company.id, employee.id, "onboard", employee.hireDate, "新增员工信息", operator.id);
        return employee;
    }

    public Employee updateEmployee(String authorization, long id, Map<String, Object> body) {
        User operator = accessControl.requirePeopleManager(authorization);
        Employee employee = require(enterpriseStore.employees.get(id), "Employee not found");
        if (!accessControl.canAccessCompany(operator, employee.companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        String oldStatus = employee.status;
        applyEmployeeFields(employee, body);
        touch(employee);
        enterpriseStore.saveEmployee(employee);
        if (!oldStatus.equals(employee.status)) {
            String eventType = employee.status.equals("departed") ? "offboard" : "status_change";
            String effectiveDate = employee.status.equals("departed") && employee.leaveDate != null ? employee.leaveDate : LocalDate.now().toString();
            enterpriseStore.event(employee.companyId, employee.id, eventType, effectiveDate, "员工状态从 " + oldStatus + " 更新为 " + employee.status, operator.id);
        }
        return employee;
    }

    public List<EmploymentEvent> listEmploymentEvents(String authorization, Long companyId) {
        Company company = accessControl.resolveCompany(accessControl.requireUser(authorization), companyId);
        return enterpriseStore.sortedEmploymentEvents(company.id);
    }

    public List<TaxItem> listTaxItems(String authorization, Long companyId) {
        Company company = accessControl.resolveCompany(accessControl.requireUser(authorization), companyId);
        return enterpriseStore.sortedTaxItems(company.id);
    }

    public TaxItem updateTaxItem(String authorization, long id, Map<String, Object> body) {
        User user = accessControl.requireAdmin(authorization);
        TaxItem item = require(enterpriseStore.taxItems.get(id), "Tax item not found");
        if (!accessControl.canAccessCompany(user, item.companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if (body.containsKey("status")) {
            item.status = text(body.get("status"));
        }
        if (body.containsKey("paidAmount")) {
            item.paidAmount = number(body.get("paidAmount"), item.paidAmount);
        }
        if (body.containsKey("note")) {
            item.note = nullableText(body.get("note"));
        }
        touch(item);
        enterpriseStore.saveTaxItem(item);
        return item;
    }

    public List<EntityTransfer> listEntityTransfers(String authorization, Long entityId) {
        User user = accessControl.requireUser(authorization);
        Long scopedEntityId = null;
        if (entityId != null && entityId > 0) {
            scopedEntityId = accessControl.resolveCompany(user, entityId).id;
        }
        List<Long> accessibleEntityIds = accessControl.accessibleCompanies(user).stream().map(company -> company.id).toList();
        return enterpriseStore.sortedEntityTransfers(accessibleEntityIds, scopedEntityId);
    }

    public EntityTransfer createEntityTransfer(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        long fromEntityId = optionalLong(body.get("fromEntityId"))
            .or(() -> optionalLong(body.get("fromCompanyId")))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "fromEntityId is required"));
        long toEntityId = optionalLong(body.get("toEntityId"))
            .or(() -> optionalLong(body.get("toCompanyId")))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "toEntityId is required"));
        if (fromEntityId == toEntityId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot transfer within the same subject");
        }
        Company fromEntity = accessControl.resolveCompany(user, fromEntityId);
        Company toEntity = accessControl.resolveCompany(user, toEntityId);
        BigDecimal amount = number(body.get("amount"), BigDecimal.ZERO);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be positive");
        }
        return enterpriseStore.entityTransfer(
            fromEntity.id,
            toEntity.id,
            textOr(body.get("transferType"), "inter_entity_transfer"),
            String.valueOf(amount),
            textOr(body.get("currency"), fromEntity.currency == null ? "CNY" : fromEntity.currency),
            textOr(body.get("transferDate"), LocalDate.now().toString()),
            nullableText(body.get("note")),
            textOr(body.get("status"), "recorded"),
            user.id
        );
    }

    private void applyCompanyFields(Company company, Map<String, Object> body) {
        if (body.containsKey("name")) {
            company.name = text(body.get("name"));
        }
        if (body.containsKey("entityType")) {
            company.entityType = text(body.get("entityType"));
        }
        if (body.containsKey("creditCode")) {
            company.creditCode = nullableText(body.get("creditCode"));
        }
        if (body.containsKey("industry")) {
            company.industry = text(body.get("industry"));
        }
        if (body.containsKey("taxpayerType")) {
            company.taxpayerType = text(body.get("taxpayerType"));
        }
        if (body.containsKey("currency")) {
            company.currency = text(body.get("currency"));
        }
        if (body.containsKey("country")) {
            company.country = text(body.get("country"));
        }
        if (body.containsKey("province")) {
            company.province = text(body.get("province"));
        }
        if (body.containsKey("city")) {
            company.city = text(body.get("city"));
        }
        if (body.containsKey("district")) {
            company.district = text(body.get("district"));
        }
        if (body.containsKey("registeredAddress")) {
            company.registeredAddress = nullableText(body.get("registeredAddress"));
        }
        if (body.containsKey("operatingRegion")) {
            company.operatingRegion = text(body.get("operatingRegion"));
        } else if (body.keySet().stream().anyMatch(key -> List.of("country", "province", "city", "district").contains(key))) {
            company.operatingRegion = List.of(company.country, company.province, company.city, company.district).stream()
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "/" + right)
                .orElse("中国");
        }
        if (body.containsKey("taxAuthority")) {
            company.taxAuthority = nullableText(body.get("taxAuthority"));
        }
        if (body.containsKey("policyProfileKey")) {
            company.policyProfileKey = text(body.get("policyProfileKey"));
        }
        if (body.containsKey("fiscalYearStartMonth")) {
            company.fiscalYearStartMonth = intValue(body.get("fiscalYearStartMonth"), company.fiscalYearStartMonth);
        }
    }

    private void applyEmployeeFields(Employee employee, Map<String, Object> body) {
        if (body.containsKey("userId")) {
            employee.userId = optionalLong(body.get("userId")).orElse(null);
        }
        if (body.containsKey("departmentId")) {
            employee.departmentId = optionalLong(body.get("departmentId")).orElse(null);
        }
        if (body.containsKey("name")) {
            employee.name = text(body.get("name"));
        }
        if (body.containsKey("email")) {
            employee.email = text(body.get("email"));
        }
        if (body.containsKey("phone")) {
            employee.phone = nullableText(body.get("phone"));
        }
        if (body.containsKey("position")) {
            employee.position = text(body.get("position"));
        }
        if (body.containsKey("employmentType")) {
            employee.employmentType = text(body.get("employmentType"));
        }
        if (body.containsKey("status")) {
            employee.status = text(body.get("status"));
        }
        if (body.containsKey("accessRole")) {
            employee.accessRole = text(body.get("accessRole"));
        }
        if (body.containsKey("accessScope")) {
            employee.accessScope = text(body.get("accessScope"));
        }
        if (body.containsKey("hireDate")) {
            employee.hireDate = text(body.get("hireDate"));
        }
        if (body.containsKey("leaveDate")) {
            employee.leaveDate = nullableText(body.get("leaveDate"));
        }
        if (body.containsKey("salary")) {
            employee.salary = number(body.get("salary"), employee.salary);
        }
        if (body.containsKey("socialInsurance")) {
            employee.socialInsurance = number(body.get("socialInsurance"), employee.socialInsurance);
        }
        if (body.containsKey("housingFund")) {
            employee.housingFund = number(body.get("housingFund"), employee.housingFund);
        }
        if (body.containsKey("taxEstimate")) {
            employee.taxEstimate = number(body.get("taxEstimate"), employee.taxEstimate);
        }
        if (body.containsKey("emergencyContact")) {
            employee.emergencyContact = nullableText(body.get("emergencyContact"));
        }
    }

    private static boolean sameMonth(String date, YearMonth month) {
        return YearMonth.from(LocalDate.parse(date)).equals(month);
    }
}
