package com.mamoji.service;

import com.mamoji.platform.tenant.Company;
import com.mamoji.people.application.DepartmentRepository;
import com.mamoji.people.application.EmployeeRepository;
import com.mamoji.people.application.EmploymentEventRepository;
import com.mamoji.people.domain.Employee;
import com.mamoji.people.domain.EmployeeCertificate;
import com.mamoji.people.domain.EmployeeCompensationPolicy;
import com.mamoji.people.domain.EmployeeExperience;
import com.mamoji.people.domain.EmploymentEvent;
import com.mamoji.platform.identity.User;
import com.mamoji.platform.product.ProductModuleCatalog;
import com.mamoji.platform.tenant.CompanyMembershipRepository;
import com.mamoji.platform.tenant.CompanyProfilePolicy;
import com.mamoji.platform.tenant.CompanyRepository;
import com.mamoji.platform.tenant.EntityTransfer;
import com.mamoji.platform.tenant.EntityTransferRepository;
import com.mamoji.platform.audit.application.AuditTrailService;
import com.mamoji.service.support.AccessControlService;
import com.mamoji.service.support.EnterprisePermissionCatalog;
import com.mamoji.tax.application.TaxItemRepository;
import com.mamoji.tax.domain.TaxItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
    private final AuditTrailService auditTrail;
    private final CompanyProvisioningService companyProvisioning;
    private final DepartmentRepository departments;
    private final EmployeeRepository employeeRepository;
    private final EmploymentEventRepository employmentEvents;
    private final TaxItemRepository taxItems;
    private final AccessControlService accessControl;
    private final EnterprisePermissionCatalog permissionCatalog;
    private final OutboxEventService outboxEventService;
    private final ProductModuleCatalog productModules;
    private final CompanyMembershipRepository memberships;
    private final CompanyRepository companies;
    private final EntityTransferRepository entityTransfers;

    public EnterpriseManagementService(
        AuditTrailService auditTrail,
        CompanyProvisioningService companyProvisioning,
        DepartmentRepository departments,
        EmployeeRepository employeeRepository,
        EmploymentEventRepository employmentEvents,
        TaxItemRepository taxItems,
        AccessControlService accessControl,
        EnterprisePermissionCatalog permissionCatalog,
        OutboxEventService outboxEventService,
        ProductModuleCatalog productModules,
        CompanyMembershipRepository memberships,
        CompanyRepository companies,
        EntityTransferRepository entityTransfers
    ) {
        this.auditTrail = auditTrail;
        this.companyProvisioning = companyProvisioning;
        this.departments = departments;
        this.employeeRepository = employeeRepository;
        this.employmentEvents = employmentEvents;
        this.taxItems = taxItems;
        this.accessControl = accessControl;
        this.permissionCatalog = permissionCatalog;
        this.outboxEventService = outboxEventService;
        this.productModules = productModules;
        this.memberships = memberships;
        this.companies = companies;
        this.entityTransfers = entityTransfers;
    }

    public Map<String, Object> summary(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        List<Employee> employees = employeeRepository.findByCompany(company.id, false);
        List<TaxItem> taxes = taxItems.findByCompany(company.id);
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
        result.put("departmentCount", departments.findByCompany(company.id).size());
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Household subjects are disabled by product configuration");
        }
        Company company = new Company();
        company.ownerId = user.id;
        company.name = textOr(body.get("name"), "新公司主体");
        company.entityType = entityType;
        company.creditCode = nullableText(body.get("creditCode"));
        company.industry = textOr(body.get("industry"), "未设置");
        company.taxpayerType = textOr(body.get("taxpayerType"), "未设置");
        company.currency = textOr(body.get("currency"), "CNY");
        CompanyProfilePolicy.initialize(company);
        applyCompanyFields(company, body);
        validateCompanyProfile(company);
        companyProvisioning.create(company);
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
        validateCompanyProfile(company);
        companies.update(company);
        audit(company.id, "company", company.id, "update", "更新公司主体: " + company.name, user);
        return company;
    }

    public List<Employee> listEmployees(String authorization, Map<String, String> params) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, optionalLong(params.get("companyId")).orElse(null));
        boolean directoryReadable = accessControl.canReadPeopleDirectory(user, company.id);
        String keyword = params.getOrDefault("keyword", "").toLowerCase();
        String status = params.getOrDefault("status", "");
        long departmentId = longParam(params, "departmentId", 0);
        return employeeRepository.findByCompany(company.id).stream()
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
        Employee employee = newEmployee(
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
        employeeRepository.insert(employee);
        memberships.synchronize(employee);
        syncEmployeeProfileLists(employee, body);
        recordEmploymentEvent(company.id, employee.id, "onboard", employee.hireDate, "新增员工信息", operator.id);
        audit(company.id, "employee", employee.id, "create", "创建员工档案: " + employee.name, operator);
        return employee;
    }

    @Transactional
    public Employee updateEmployee(String authorization, long id, Map<String, Object> body) {
        User operator = accessControl.requireUser(authorization);
        Employee employee = employeeRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
        if (!accessControl.canAccessCompany(operator, employee.companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        requireEmployeeUpdatePermission(operator, employee.companyId, body);
        String oldStatus = employee.status;
        applyEmployeeFields(employee, body);
        touch(employee);
        employeeRepository.update(employee);
        memberships.synchronize(employee);
        syncEmployeeProfileLists(employee, body);
        if (!oldStatus.equals(employee.status)) {
            String eventType = employee.status.equals("departed") ? "offboard" : "status_change";
            String effectiveDate = employee.status.equals("departed") && employee.leaveDate != null ? employee.leaveDate : LocalDate.now().toString();
            recordEmploymentEvent(
                employee.companyId,
                employee.id,
                eventType,
                effectiveDate,
                "员工状态从 " + oldStatus + " 更新为 " + employee.status,
                operator.id
            );
        }
        audit(employee.companyId, "employee", employee.id, "update", "更新员工档案: " + employee.name, operator);
        return employee;
    }

    public List<EmploymentEvent> listEmploymentEvents(String authorization, Long companyId) {
        Company company = accessControl.resolveCompany(accessControl.requireUser(authorization), companyId);
        return employmentEvents.findByCompany(company.id);
    }

    public List<EntityTransfer> listEntityTransfers(String authorization, Long entityId) {
        User user = accessControl.requireUser(authorization);
        Long scopedEntityId = null;
        if (entityId != null && entityId > 0) {
            scopedEntityId = accessControl.resolveCompany(user, entityId).id;
        }
        List<Long> accessibleEntityIds = accessControl.accessibleCompanies(user).stream().map(company -> company.id).toList();
        return entityTransfers.findAccessible(accessibleEntityIds, scopedEntityId);
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
        EntityTransfer transfer = new EntityTransfer();
        transfer.fromEntityId = fromEntity.id;
        transfer.toEntityId = toEntity.id;
        transfer.transferType = textOr(body.get("transferType"), "inter_entity_transfer");
        transfer.amount = amount;
        transfer.currency = textOr(body.get("currency"), fromEntity.currency == null ? "CNY" : fromEntity.currency);
        transfer.transferDate = textOr(body.get("transferDate"), LocalDate.now().toString());
        transfer.note = nullableText(body.get("note"));
        transfer.status = textOr(body.get("status"), "recorded");
        transfer.operatorUserId = user.id;
        EntityTransfer persistedTransfer;
        try {
            persistedTransfer = entityTransfers.append(transfer);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
        audit(fromEntity.id, "entity_transfer", persistedTransfer.id, "create", "创建主体间资金划转: " + fromEntity.name + " -> " + toEntity.name, user);
        return persistedTransfer;
    }

    private void audit(long companyId, String entityType, long entityId, String action, String summary, User user) {
        auditTrail.record(companyId, entityType, entityId, action, summary, user.id, user.nickname);
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

    private EmploymentEvent recordEmploymentEvent(
        long companyId,
        long employeeId,
        String type,
        String effectiveDate,
        String note,
        long operatorUserId
    ) {
        EmploymentEvent event = new EmploymentEvent();
        event.companyId = companyId;
        event.employeeId = employeeId;
        event.type = type;
        event.effectiveDate = effectiveDate;
        event.note = note;
        event.operatorUserId = operatorUserId;
        return employmentEvents.append(event);
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
            company.operatingRegion = CompanyProfilePolicy.regionLabel(company);
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

    private void validateCompanyProfile(Company company) {
        try {
            CompanyProfilePolicy.normalizeAndValidate(company);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
        if ("household".equals(company.entityType) && !productModules.householdEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Household subjects are disabled by product configuration");
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

    private Employee newEmployee(
        long companyId,
        Long userId,
        Long departmentId,
        String name,
        String email,
        String phone,
        String position,
        String employmentType,
        String status,
        String accessRole,
        String accessScope,
        String hireDate,
        String leaveDate,
        String salary,
        String socialInsurance,
        String housingFund,
        String taxEstimate,
        String monthlyCost,
        String emergencyContact
    ) {
        Employee employee = new Employee();
        employee.companyId = companyId;
        employee.userId = userId;
        employee.departmentId = departmentId;
        employee.name = name;
        employee.email = email;
        employee.phone = phone;
        employee.position = position;
        employee.employmentType = employmentType;
        employee.status = status;
        employee.accessRole = accessRole == null ? "employee" : accessRole;
        employee.accessScope = accessScope == null ? "self" : accessScope;
        employee.hireDate = hireDate;
        employee.leaveDate = leaveDate;
        employee.salary = number(salary, BigDecimal.ZERO);
        employee.socialInsurance = number(socialInsurance, BigDecimal.ZERO);
        employee.housingFund = number(housingFund, BigDecimal.ZERO);
        employee.taxEstimate = number(taxEstimate, BigDecimal.ZERO);
        employee.emergencyContact = emergencyContact;
        String now = OffsetDateTime.now().toString();
        employee.createdAt = now;
        employee.updatedAt = now;
        EmployeeCompensationPolicy.initialize(employee, number(monthlyCost, BigDecimal.ZERO));
        return employee;
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
            employee.certificates = employeeRepository.replaceCertificates(
                employee.id,
                employeeCertificatesFrom(body.get("certificates"))
            );
        }
        if (body.containsKey("experiences")) {
            employee.experiences = employeeRepository.replaceExperiences(
                employee.id,
                employeeExperiencesFrom(body.get("experiences"))
            );
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

}
