package com.mamoji.service;

import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.Department;
import com.mamoji.domain.Models.Employee;
import com.mamoji.domain.Models.EmployeeCertificate;
import com.mamoji.domain.Models.EmployeeExperience;
import com.mamoji.domain.Models.EntityTransfer;
import com.mamoji.domain.Models.EmploymentEvent;
import com.mamoji.domain.Models.TaxItem;
import com.mamoji.domain.Models.User;
import com.mamoji.platform.product.ProductModuleCatalog;
import com.mamoji.platform.tenant.CompanyMembershipRepository;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import com.mamoji.service.support.EnterprisePermissionCatalog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final InMemoryStore coreStore;
    private final AccessControlService accessControl;
    private final EnterprisePermissionCatalog permissionCatalog;
    private final OutboxEventService outboxEventService;
    private final ProductModuleCatalog productModules;
    private final CompanyMembershipRepository memberships;

    public EnterpriseManagementService(
        EnterpriseStore enterpriseStore,
        InMemoryStore coreStore,
        AccessControlService accessControl,
        EnterprisePermissionCatalog permissionCatalog,
        OutboxEventService outboxEventService,
        ProductModuleCatalog productModules,
        CompanyMembershipRepository memberships
    ) {
        this.enterpriseStore = enterpriseStore;
        this.coreStore = coreStore;
        this.accessControl = accessControl;
        this.permissionCatalog = permissionCatalog;
        this.outboxEventService = outboxEventService;
        this.productModules = productModules;
        this.memberships = memberships;
    }

    public Map<String, Object> summary(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        List<Employee> employees = enterpriseStore.sortedEmployees(company.id, false);
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
        return accessControl.accessibleCompanies(accessControl.requireUser(authorization)).stream()
            .filter(company -> productModules.householdEnabled() || !"household".equals(company.entityType))
            .toList();
    }

    @Transactional
    public Company createCompany(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        String entityType = textOr(body.get("entityType"), "company");
        if ("household".equals(entityType) && !productModules.householdEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Household subjects are disabled in internal-module mode");
        }
        Company company = enterpriseStore.company(
            user.id,
            textOr(body.get("name"), "新公司主体"),
            entityType,
            nullableText(body.get("creditCode")),
            textOr(body.get("industry"), "未设置"),
            textOr(body.get("taxpayerType"), "未设置"),
            textOr(body.get("currency"), "CNY")
        );
        applyCompanyFields(company, body);
        touch(company);
        enterpriseStore.saveCompany(company);
        memberships.ensureOwner(company);
        coreStore.ensureCompanyAccountingWorkspace(user.id, company.id, company.currency, company.name);
        audit(company.id, "company", company.id, "create", "创建公司主体: " + company.name, user);
        return company;
    }

    public Company companyProfile(String authorization, Long companyId) {
        return accessControl.resolveCompany(accessControl.requireUser(authorization), companyId);
    }

    @Transactional
    public Company updateCompanyProfile(String authorization, Long companyId, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        accessControl.requirePeopleManager(user, company.id);
        applyCompanyFields(company, body);
        touch(company);
        enterpriseStore.saveCompany(company);
        audit(company.id, "company", company.id, "update", "更新公司主体: " + company.name, user);
        return company;
    }

    public List<Department> listDepartments(String authorization, Long companyId) {
        Company company = accessControl.resolveCompany(accessControl.requireUser(authorization), companyId);
        return enterpriseStore.sortedDepartments(company.id);
    }

    @Transactional
    public Department createDepartment(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, optionalLong(body.get("companyId")).orElse(null));
        accessControl.requirePeopleManager(user, company.id);
        Department department = enterpriseStore.department(
            company.id,
            textOr(body.get("name"), "新部门"),
            textOr(body.get("costCenter"), "GENERAL"),
            String.valueOf(number(body.get("budget"), BigDecimal.ZERO))
        );
        applyDepartmentFields(department, body);
        touch(department);
        enterpriseStore.saveDepartment(department);
        audit(company.id, "department", department.id, "create", "创建部门: " + department.name, user);
        return department;
    }

    @Transactional
    public Department updateDepartment(String authorization, long id, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        Department department = enterpriseStore.findDepartment(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found"));
        if (!accessControl.canAccessCompany(user, department.companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        accessControl.requirePeopleManager(user, department.companyId);
        applyDepartmentFields(department, body);
        touch(department);
        enterpriseStore.saveDepartment(department);
        audit(department.companyId, "department", department.id, "update", "更新部门: " + department.name, user);
        return department;
    }

    public List<Employee> listEmployees(String authorization, Map<String, String> params) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, optionalLong(params.get("companyId")).orElse(null));
        boolean directoryReadable = accessControl.canReadPeopleDirectory(user, company.id);
        String keyword = params.getOrDefault("keyword", "").toLowerCase();
        String status = params.getOrDefault("status", "");
        long departmentId = longParam(params, "departmentId", 0);
        return enterpriseStore.sortedEmployees(company.id).stream()
            .filter(employee -> directoryReadable || (employee.userId != null && employee.userId == user.id))
            .filter(employee -> keyword.isBlank()
                || employee.name.toLowerCase().contains(keyword)
                || employee.email.toLowerCase().contains(keyword)
                || employee.position.toLowerCase().contains(keyword)
                || contains(employee.employeeNo, keyword)
                || contains(employee.legalName, keyword)
                || contains(employee.preferredName, keyword)
                || contains(employee.jobLevel, keyword)
                || contains(employee.workLocation, keyword)
                || contains(employee.educationLevel, keyword)
                || contains(employee.graduationSchool, keyword)
                || contains(employee.major, keyword)
                || contains(employee.skillTags, keyword)
                || (employee.departmentName != null && employee.departmentName.toLowerCase().contains(keyword)))
            .filter(employee -> status.isBlank() || employee.status.equals(status))
            .filter(employee -> departmentId == 0 || (employee.departmentId != null && employee.departmentId == departmentId))
            .toList();
    }

    @Transactional
    public Employee createEmployee(String authorization, Map<String, Object> body) {
        User operator = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(operator, optionalLong(body.get("companyId")).orElse(null));
        accessControl.requirePeopleManager(operator, company.id);
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
        applyEmployeeFields(employee, body);
        touch(employee);
        enterpriseStore.saveEmployee(employee);
        memberships.synchronize(employee);
        syncEmployeeProfileLists(employee, body);
        enterpriseStore.event(company.id, employee.id, "onboard", employee.hireDate, "新增员工信息", operator.id);
        audit(company.id, "employee", employee.id, "create", "创建员工档案: " + employee.name, operator);
        return employee;
    }

    @Transactional
    public Employee updateEmployee(String authorization, long id, Map<String, Object> body) {
        User operator = accessControl.requireUser(authorization);
        Employee employee = enterpriseStore.findEmployee(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
        if (!accessControl.canAccessCompany(operator, employee.companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        requireEmployeeUpdatePermission(operator, employee.companyId, body);
        String oldStatus = employee.status;
        applyEmployeeFields(employee, body);
        touch(employee);
        enterpriseStore.saveEmployee(employee);
        memberships.synchronize(employee);
        syncEmployeeProfileLists(employee, body);
        if (!oldStatus.equals(employee.status)) {
            String eventType = employee.status.equals("departed") ? "offboard" : "status_change";
            String effectiveDate = employee.status.equals("departed") && employee.leaveDate != null ? employee.leaveDate : LocalDate.now().toString();
            enterpriseStore.event(employee.companyId, employee.id, eventType, effectiveDate, "员工状态从 " + oldStatus + " 更新为 " + employee.status, operator.id);
        }
        audit(employee.companyId, "employee", employee.id, "update", "更新员工档案: " + employee.name, operator);
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

    @Transactional
    public TaxItem createTaxItem(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, optionalLong(body.get("companyId")).orElse(null));
        accessControl.requireFinanceManager(user, company.id);
        TaxItem item = enterpriseStore.taxItem(
            company.id,
            textOr(body.get("name"), "新税务事项"),
            textOr(body.get("period"), YearMonth.now().toString()),
            textOr(body.get("taxType"), "vat"),
            String.valueOf(number(body.get("taxableAmount"), BigDecimal.ZERO)),
            String.valueOf(number(body.get("taxAmount"), BigDecimal.ZERO)),
            String.valueOf(number(body.get("paidAmount"), BigDecimal.ZERO)),
            textOr(body.get("dueDate"), LocalDate.now().plusDays(15).toString()),
            textOr(body.get("status"), "estimated"),
            nullableText(body.get("note"))
        );
        applyTaxItemFields(item, body);
        syncTaxItemDerivedFields(item, !body.containsKey("status"));
        touch(item);
        enterpriseStore.saveTaxItem(item);
        audit(company.id, "tax_item", item.id, "create", "创建税费事项: " + item.name, user);
        return item;
    }

    @Transactional
    public TaxItem updateTaxItem(String authorization, long id, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        TaxItem item = enterpriseStore.findTaxItem(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tax item not found"));
        accessControl.resolveCompany(user, item.companyId);
        accessControl.requireFinanceManager(user, item.companyId);
        applyTaxItemFields(item, body);
        syncTaxItemDerivedFields(item, !body.containsKey("status"));
        touch(item);
        enterpriseStore.saveTaxItem(item);
        audit(item.companyId, "tax_item", item.id, "update", "更新税费事项: " + item.name, user);
        return item;
    }

    @Transactional
    public void deleteTaxItem(String authorization, long id) {
        User user = accessControl.requireUser(authorization);
        TaxItem item = enterpriseStore.findTaxItem(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tax item not found"));
        accessControl.resolveCompany(user, item.companyId);
        accessControl.requireFinanceManager(user, item.companyId);
        audit(item.companyId, "tax_item", item.id, "delete", "删除税费事项: " + item.name, user);
        enterpriseStore.deleteTaxItem(id);
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

    @Transactional
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
        EntityTransfer transfer = enterpriseStore.entityTransfer(
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
        audit(fromEntity.id, "entity_transfer", transfer.id, "create", "创建主体间资金划转: " + fromEntity.name + " -> " + toEntity.name, user);
        return transfer;
    }

    private void audit(long companyId, String entityType, long entityId, String action, String summary, User user) {
        enterpriseStore.auditLog(companyId, entityType, entityId, action, summary, user.id, user.nickname);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        payload.put("actorName", user.nickname);
        payload.put("entityType", entityType);
        payload.put("action", action);
        outboxEventService.publish(
            "enterprise." + entityType + "." + action,
            companyId,
            entityType,
            entityId,
            user.id,
            payload
        );
    }

    private void applyTaxItemFields(TaxItem item, Map<String, Object> body) {
        if (body.containsKey("name")) {
            item.name = text(body.get("name"));
        }
        if (body.containsKey("period")) {
            item.period = text(body.get("period"));
        }
        if (body.containsKey("taxType")) {
            item.taxType = text(body.get("taxType"));
        }
        if (body.containsKey("taxableAmount")) {
            item.taxableAmount = number(body.get("taxableAmount"), item.taxableAmount);
        }
        if (body.containsKey("taxAmount")) {
            item.taxAmount = number(body.get("taxAmount"), item.taxAmount);
        }
        if (body.containsKey("paidAmount")) {
            item.paidAmount = number(body.get("paidAmount"), item.paidAmount);
        }
        if (body.containsKey("deductibleAmount")) {
            item.deductibleAmount = number(body.get("deductibleAmount"), item.deductibleAmount);
        }
        if (body.containsKey("taxRate")) {
            item.taxRate = number(body.get("taxRate"), item.taxRate);
        } else {
            item.taxRate = inferredTaxRate(item);
        }
        if (body.containsKey("dueDate")) {
            item.dueDate = text(body.get("dueDate"));
        }
        if (body.containsKey("status")) {
            item.status = normalizeTaxStatus(text(body.get("status")));
        }
        if (body.containsKey("filingStatus")) {
            item.filingStatus = normalizeFilingStatus(text(body.get("filingStatus")));
        }
        if (body.containsKey("paymentStatus")) {
            item.paymentStatus = normalizePaymentStatus(text(body.get("paymentStatus")));
        }
        if (body.containsKey("frequency")) {
            item.frequency = normalizeFrequency(text(body.get("frequency")));
        }
        if (body.containsKey("declarationDate")) {
            item.declarationDate = nullableText(body.get("declarationDate"));
        }
        if (body.containsKey("paymentDate")) {
            item.paymentDate = nullableText(body.get("paymentDate"));
        }
        if (body.containsKey("responsiblePerson")) {
            item.responsiblePerson = nullableText(body.get("responsiblePerson"));
        }
        if (body.containsKey("riskLevel")) {
            item.riskLevel = normalizeRiskLevel(text(body.get("riskLevel")));
        }
        if (body.containsKey("policyBasis")) {
            item.policyBasis = nullableText(body.get("policyBasis"));
        }
        if (body.containsKey("sourceType")) {
            item.sourceType = normalizeSourceType(text(body.get("sourceType")));
        }
        if (body.containsKey("note")) {
            item.note = nullableText(body.get("note"));
        }
    }

    private void syncTaxItemDerivedFields(TaxItem item, boolean allowStatusSync) {
        if ("paid".equals(item.status) && nullToZero(item.paidAmount).compareTo(nullToZero(item.taxAmount)) < 0) {
            item.paidAmount = nullToZero(item.taxAmount);
        }
        item.paymentStatus = normalizePaymentStatus(paymentStatusFor(item));
        if (item.filingStatus == null || item.filingStatus.isBlank()) {
            item.filingStatus = filingStatusFor(item.status);
        }
        if (item.frequency == null || item.frequency.isBlank()) {
            item.frequency = frequencyFor(item.period);
        }
        if (item.responsiblePerson == null || item.responsiblePerson.isBlank()) {
            item.responsiblePerson = "财务负责人";
        }
        if (item.policyBasis == null || item.policyBasis.isBlank()) {
            item.policyBasis = enterpriseStore.findCompany(item.companyId)
                .map(company -> company.policyProfileKey)
                .orElse("CN-DEFAULT-DEMO-POLICY");
        }
        if (item.sourceType == null || item.sourceType.isBlank()) {
            item.sourceType = "manual";
        }
        if ("paid".equals(item.paymentStatus)) {
            item.filingStatus = "accepted";
            if (allowStatusSync) {
                item.status = "paid";
            }
            if (item.paymentDate == null || item.paymentDate.isBlank()) {
                item.paymentDate = LocalDate.now().toString();
            }
        } else if (allowStatusSync && parseDate(item.dueDate).isBefore(LocalDate.now())) {
            item.status = "overdue";
            item.filingStatus = "overdue";
        }
        item.riskLevel = taxRiskLevel(item);
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

    private void applyDepartmentFields(Department department, Map<String, Object> body) {
        if (body.containsKey("name")) {
            department.name = textOr(body.get("name"), department.name);
        }
        if (body.containsKey("costCenter")) {
            department.costCenter = textOr(body.get("costCenter"), department.costCenter);
        }
        if (body.containsKey("budget")) {
            department.budget = number(body.get("budget"), department.budget);
        }
        if (body.containsKey("managerEmployeeId")) {
            department.managerEmployeeId = optionalLong(body.get("managerEmployeeId")).orElse(null);
        }
        if (body.containsKey("status")) {
            department.status = intValue(body.get("status"), department.status);
        }
    }

    private void requireEmployeeUpdatePermission(User operator, long companyId, Map<String, Object> body) {
        boolean payrollChange = body.keySet().stream().anyMatch(this::isPayrollField);
        boolean peopleChange = body.keySet().stream().anyMatch(field -> !isPayrollField(field));
        if (peopleChange) {
            accessControl.requirePeopleManager(operator, companyId);
        }
        if (payrollChange) {
            accessControl.requirePayrollManager(operator, companyId);
        }
        if (body.containsKey("accessRole") || body.containsKey("accessScope")) {
            if (!accessControl.hasCompanyManagementRole(operator, companyId, "founder")) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Founder permission required");
            }
        }
    }

    private boolean isPayrollField(String field) {
        return List.of(
            "salary",
            "overtimeBase",
            "weekdayOvertimeHours",
            "restDayOvertimeHours",
            "holidayOvertimeHours",
            "overtimePay",
            "overtimePolicyNote",
            "socialInsurance",
            "housingFund",
            "taxEstimate",
            "socialInsuranceBase",
            "socialInsurancePersonalRate",
            "socialInsuranceCompanyRate",
            "socialInsuranceRegion",
            "hukouType",
            "medicalTier",
            "pensionBase",
            "medicalBase",
            "unemploymentBase",
            "workInjuryBase",
            "maternityBase",
            "workInjuryCompanyRate",
            "socialInsurancePolicyNote",
            "housingFundBase",
            "housingFundPersonalRate",
            "housingFundCompanyRate",
            "personalDeduction"
        ).contains(field);
    }

    private void applyEmployeeFields(Employee employee, Map<String, Object> body) {
        if (body.containsKey("userId")) {
            employee.userId = optionalLong(body.get("userId")).orElse(null);
        }
        if (body.containsKey("departmentId")) {
            employee.departmentId = optionalLong(body.get("departmentId")).orElse(null);
        }
        if (body.containsKey("employeeNo")) {
            employee.employeeNo = nullableText(body.get("employeeNo"));
        }
        if (body.containsKey("name")) {
            employee.name = text(body.get("name"));
        }
        if (body.containsKey("legalName")) {
            employee.legalName = nullableText(body.get("legalName"));
        }
        if (body.containsKey("preferredName")) {
            employee.preferredName = nullableText(body.get("preferredName"));
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
        if (body.containsKey("directManagerEmployeeId")) {
            employee.directManagerEmployeeId = optionalLong(body.get("directManagerEmployeeId")).orElse(null);
        }
        if (body.containsKey("jobLevel")) {
            employee.jobLevel = nullableText(body.get("jobLevel"));
        }
        if (body.containsKey("workLocation")) {
            employee.workLocation = nullableText(body.get("workLocation"));
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
        if (body.containsKey("probationStartDate")) {
            employee.probationStartDate = nullableText(body.get("probationStartDate"));
        }
        if (body.containsKey("probationEndDate")) {
            employee.probationEndDate = nullableText(body.get("probationEndDate"));
        }
        if (body.containsKey("contractStartDate")) {
            employee.contractStartDate = nullableText(body.get("contractStartDate"));
        }
        if (body.containsKey("contractEndDate")) {
            employee.contractEndDate = nullableText(body.get("contractEndDate"));
        }
        if (body.containsKey("contractType")) {
            employee.contractType = nullableText(body.get("contractType"));
        }
        if (body.containsKey("contractStatus")) {
            employee.contractStatus = nullableText(body.get("contractStatus"));
        }
        if (body.containsKey("educationLevel")) {
            employee.educationLevel = nullableText(body.get("educationLevel"));
        }
        if (body.containsKey("graduationSchool")) {
            employee.graduationSchool = nullableText(body.get("graduationSchool"));
        }
        if (body.containsKey("major")) {
            employee.major = nullableText(body.get("major"));
        }
        if (body.containsKey("graduationDate")) {
            employee.graduationDate = nullableText(body.get("graduationDate"));
        }
        if (body.containsKey("graduationYear")) {
            int year = intValue(body.get("graduationYear"), 0);
            employee.graduationYear = year <= 0 ? null : year;
        }
        if (body.containsKey("graduateStatus")) {
            employee.graduateStatus = nullableText(body.get("graduateStatus"));
        }
        if (body.containsKey("skillTags")) {
            employee.skillTags = nullableText(body.get("skillTags"));
        }
        if (body.containsKey("resumeSummary")) {
            employee.resumeSummary = nullableText(body.get("resumeSummary"));
        }
        if (body.containsKey("materialStatus")) {
            employee.materialStatus = nullableText(body.get("materialStatus"));
        }
        if (body.containsKey("profileVerifiedAt")) {
            employee.profileVerifiedAt = nullableText(body.get("profileVerifiedAt"));
        }
        if (body.containsKey("profileVerifiedBy")) {
            employee.profileVerifiedBy = optionalLong(body.get("profileVerifiedBy")).orElse(null);
        }
        if (body.containsKey("salary")) {
            employee.salary = number(body.get("salary"), employee.salary);
        }
        if (body.containsKey("overtimeBase")) {
            employee.overtimeBase = number(body.get("overtimeBase"), employee.overtimeBase);
        }
        if (body.containsKey("weekdayOvertimeHours")) {
            employee.weekdayOvertimeHours = number(body.get("weekdayOvertimeHours"), employee.weekdayOvertimeHours);
        }
        if (body.containsKey("restDayOvertimeHours")) {
            employee.restDayOvertimeHours = number(body.get("restDayOvertimeHours"), employee.restDayOvertimeHours);
        }
        if (body.containsKey("holidayOvertimeHours")) {
            employee.holidayOvertimeHours = number(body.get("holidayOvertimeHours"), employee.holidayOvertimeHours);
        }
        if (body.containsKey("overtimePay")) {
            employee.overtimePay = number(body.get("overtimePay"), employee.overtimePay);
        }
        if (body.containsKey("overtimePolicyNote")) {
            employee.overtimePolicyNote = nullableText(body.get("overtimePolicyNote"));
        }
        if (body.containsKey("socialInsurance")) {
            employee.socialInsurance = number(body.get("socialInsurance"), employee.socialInsurance);
            if (!body.containsKey("socialInsuranceCompanyRate")) {
                employee.socialInsuranceCompanyRate = rateFromAmount(employee.socialInsurance, employee.socialInsuranceBase, employee.salary, employee.socialInsuranceCompanyRate);
            }
        }
        if (body.containsKey("housingFund")) {
            employee.housingFund = number(body.get("housingFund"), employee.housingFund);
            if (!body.containsKey("housingFundCompanyRate")) {
                employee.housingFundCompanyRate = rateFromAmount(employee.housingFund, employee.housingFundBase, employee.salary, employee.housingFundCompanyRate);
            }
        }
        if (body.containsKey("taxEstimate")) {
            employee.taxEstimate = number(body.get("taxEstimate"), employee.taxEstimate);
        }
        if (body.containsKey("socialInsuranceBase")) {
            employee.socialInsuranceBase = number(body.get("socialInsuranceBase"), employee.socialInsuranceBase);
        }
        if (body.containsKey("socialInsurancePersonalRate")) {
            employee.socialInsurancePersonalRate = number(body.get("socialInsurancePersonalRate"), employee.socialInsurancePersonalRate);
        }
        if (body.containsKey("socialInsuranceCompanyRate")) {
            employee.socialInsuranceCompanyRate = number(body.get("socialInsuranceCompanyRate"), employee.socialInsuranceCompanyRate);
        }
        if (body.containsKey("socialInsuranceRegion")) {
            employee.socialInsuranceRegion = textOr(body.get("socialInsuranceRegion"), employee.socialInsuranceRegion);
        }
        if (body.containsKey("hukouType")) {
            employee.hukouType = textOr(body.get("hukouType"), employee.hukouType);
        }
        if (body.containsKey("medicalTier")) {
            employee.medicalTier = textOr(body.get("medicalTier"), employee.medicalTier);
        }
        if (body.containsKey("pensionBase")) {
            employee.pensionBase = number(body.get("pensionBase"), employee.pensionBase);
        }
        if (body.containsKey("medicalBase")) {
            employee.medicalBase = number(body.get("medicalBase"), employee.medicalBase);
        }
        if (body.containsKey("unemploymentBase")) {
            employee.unemploymentBase = number(body.get("unemploymentBase"), employee.unemploymentBase);
        }
        if (body.containsKey("workInjuryBase")) {
            employee.workInjuryBase = number(body.get("workInjuryBase"), employee.workInjuryBase);
        }
        if (body.containsKey("maternityBase")) {
            employee.maternityBase = number(body.get("maternityBase"), employee.maternityBase);
        }
        if (body.containsKey("workInjuryCompanyRate")) {
            employee.workInjuryCompanyRate = number(body.get("workInjuryCompanyRate"), employee.workInjuryCompanyRate);
        }
        if (body.containsKey("socialInsurancePolicyNote")) {
            employee.socialInsurancePolicyNote = nullableText(body.get("socialInsurancePolicyNote"));
        }
        if (body.containsKey("housingFundBase")) {
            employee.housingFundBase = number(body.get("housingFundBase"), employee.housingFundBase);
        }
        if (body.containsKey("housingFundPersonalRate")) {
            employee.housingFundPersonalRate = number(body.get("housingFundPersonalRate"), employee.housingFundPersonalRate);
        }
        if (body.containsKey("housingFundCompanyRate")) {
            employee.housingFundCompanyRate = number(body.get("housingFundCompanyRate"), employee.housingFundCompanyRate);
        }
        if (body.containsKey("personalDeduction")) {
            employee.personalDeduction = number(body.get("personalDeduction"), employee.personalDeduction);
        }
        if (body.containsKey("emergencyContact")) {
            employee.emergencyContact = nullableText(body.get("emergencyContact"));
        }
    }

    private void syncEmployeeProfileLists(Employee employee, Map<String, Object> body) {
        if (body.containsKey("certificates")) {
            enterpriseStore.replaceEmployeeCertificates(employee.id, employeeCertificatesFrom(body.get("certificates")));
        }
        if (body.containsKey("experiences")) {
            enterpriseStore.replaceEmployeeExperiences(employee.id, employeeExperiencesFrom(body.get("experiences")));
        }
    }

    private List<EmployeeCertificate> employeeCertificatesFrom(Object payload) {
        if (!(payload instanceof List<?> rows)) {
            return List.of();
        }
        List<EmployeeCertificate> certificates = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> values)) {
                continue;
            }
            EmployeeCertificate certificate = new EmployeeCertificate();
            certificate.name = nullableText(values.get("name"));
            certificate.category = nullableText(values.get("category"));
            certificate.level = nullableText(values.get("level"));
            certificate.issuer = nullableText(values.get("issuer"));
            certificate.certificateNo = nullableText(values.get("certificateNo"));
            certificate.issueDate = nullableText(values.get("issueDate"));
            certificate.expiryDate = nullableText(values.get("expiryDate"));
            certificate.verificationStatus = textOr(values.get("verificationStatus"), "unverified");
            certificate.materialStatus = textOr(values.get("materialStatus"), "missing");
            certificate.note = nullableText(values.get("note"));
            certificates.add(certificate);
        }
        return certificates;
    }

    private List<EmployeeExperience> employeeExperiencesFrom(Object payload) {
        if (!(payload instanceof List<?> rows)) {
            return List.of();
        }
        List<EmployeeExperience> experiences = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> values)) {
                continue;
            }
            EmployeeExperience experience = new EmployeeExperience();
            experience.type = textOr(values.get("type"), "work");
            experience.organization = nullableText(values.get("organization"));
            experience.title = nullableText(values.get("title"));
            experience.startDate = nullableText(values.get("startDate"));
            experience.endDate = nullableText(values.get("endDate"));
            experience.description = nullableText(values.get("description"));
            experience.achievements = nullableText(values.get("achievements"));
            experience.skills = nullableText(values.get("skills"));
            experiences.add(experience);
        }
        return experiences;
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private static BigDecimal rateFromAmount(BigDecimal amount, BigDecimal base, BigDecimal fallbackBase, BigDecimal fallbackRate) {
        BigDecimal safeBase = positiveOr(base, fallbackBase);
        BigDecimal safeAmount = amount == null ? BigDecimal.ZERO : amount;
        if (safeBase.signum() <= 0 || safeAmount.signum() <= 0) {
            return fallbackRate == null ? BigDecimal.ZERO : fallbackRate;
        }
        return safeAmount.multiply(BigDecimal.valueOf(100)).divide(safeBase, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal positiveOr(BigDecimal value, BigDecimal fallback) {
        if (value != null && value.signum() > 0) {
            return value;
        }
        return fallback == null ? BigDecimal.ZERO : fallback;
    }

    private static boolean sameMonth(String date, YearMonth month) {
        return YearMonth.from(LocalDate.parse(date)).equals(month);
    }

    private BigDecimal inferredTaxRate(TaxItem item) {
        BigDecimal taxableAmount = nullToZero(item.taxableAmount);
        if (taxableAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return nullToZero(item.taxAmount)
            .multiply(new BigDecimal("100"))
            .divide(taxableAmount, 2, java.math.RoundingMode.HALF_UP);
    }

    private String paymentStatusFor(TaxItem item) {
        BigDecimal taxAmount = nullToZero(item.taxAmount);
        BigDecimal paidAmount = nullToZero(item.paidAmount);
        if (taxAmount.compareTo(BigDecimal.ZERO) <= 0 || paidAmount.compareTo(taxAmount) >= 0) {
            return "paid";
        }
        if (paidAmount.compareTo(BigDecimal.ZERO) > 0) {
            return "partial";
        }
        return "unpaid";
    }

    private String filingStatusFor(String status) {
        return switch (status == null ? "" : status) {
            case "paid" -> "accepted";
            case "pending" -> "submitted";
            case "overdue" -> "overdue";
            case "estimated" -> "prepared";
            default -> "not_started";
        };
    }

    private String frequencyFor(String period) {
        if (period == null) {
            return "monthly";
        }
        String normalized = period.toUpperCase();
        if (normalized.contains("Q")) {
            return "quarterly";
        }
        if (normalized.matches("\\d{4}")) {
            return "annual";
        }
        return "monthly";
    }

    private LocalDate parseDate(String value) {
        try {
            return value == null || value.isBlank() ? LocalDate.now() : LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            return LocalDate.now();
        }
    }

    private String taxRiskLevel(TaxItem item) {
        BigDecimal unpaid = nullToZero(item.taxAmount).subtract(nullToZero(item.paidAmount));
        if (unpaid.compareTo(BigDecimal.ZERO) <= 0 || "paid".equals(item.status)) {
            return "low";
        }
        LocalDate dueDate = parseDate(item.dueDate);
        LocalDate today = LocalDate.now();
        if (dueDate.isBefore(today) || "overdue".equals(item.status)) {
            return "high";
        }
        if (!dueDate.isAfter(today.plusDays(7)) || unpaid.compareTo(new BigDecimal("50000")) >= 0) {
            return "medium";
        }
        if ("manual".equals(item.sourceType) || "not_started".equals(item.filingStatus)) {
            return "medium";
        }
        return "low";
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String normalizeTaxStatus(String value) {
        return switch (value) {
            case "estimated", "pending", "paid", "overdue" -> value;
            default -> "estimated";
        };
    }

    private String normalizeFilingStatus(String value) {
        return switch (value) {
            case "not_started", "prepared", "submitted", "accepted", "overdue" -> value;
            default -> "not_started";
        };
    }

    private String normalizePaymentStatus(String value) {
        return switch (value) {
            case "unpaid", "partial", "paid" -> value;
            default -> "unpaid";
        };
    }

    private String normalizeFrequency(String value) {
        return switch (value) {
            case "monthly", "quarterly", "annual", "one_time" -> value;
            default -> "monthly";
        };
    }

    private String normalizeRiskLevel(String value) {
        return switch (value) {
            case "low", "medium", "high" -> value;
            default -> "medium";
        };
    }

    private String normalizeSourceType(String value) {
        return switch (value) {
            case "manual", "demo_estimate", "transaction", "receipt", "payroll", "policy" -> value;
            default -> "manual";
        };
    }
}
