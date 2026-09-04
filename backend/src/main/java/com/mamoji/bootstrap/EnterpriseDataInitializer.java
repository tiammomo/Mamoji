package com.mamoji.bootstrap;

import com.mamoji.people.application.DepartmentRepository;
import com.mamoji.people.application.EmployeeRepository;
import com.mamoji.people.application.EmploymentEventRepository;
import com.mamoji.people.domain.Department;
import com.mamoji.people.domain.Employee;
import com.mamoji.people.domain.EmployeeCompensationPolicy;
import com.mamoji.people.domain.EmploymentEvent;
import com.mamoji.platform.audit.application.AuditTrailService;
import com.mamoji.platform.identity.account.application.UserDirectory;
import com.mamoji.platform.tenant.Company;
import com.mamoji.platform.tenant.CompanyMembershipRepository;
import com.mamoji.platform.tenant.CompanyProfilePolicy;
import com.mamoji.platform.tenant.CompanyRepository;
import com.mamoji.service.CompanyProvisioningService;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** Creates the minimal first enterprise only in explicit production bootstrap mode. */
@Component
@ConditionalOnProperty(name = "mamoji.bootstrap.mode", havingValue = "bootstrap")
@DependsOn("initialAdminDataInitializer")
public class EnterpriseDataInitializer {
    private final UserDirectory userDirectory;
    private final AuditTrailService auditTrail;
    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final EmploymentEventRepository employmentEventRepository;
    private final CompanyRepository companyRepository;
    private final CompanyMembershipRepository companyMembershipRepository;
    private final CompanyProvisioningService companyProvisioning;
    private final String companyName;
    private final String companyCreditCode;
    private final String companyIndustry;
    private final String companyTaxpayerType;
    private final String companyCurrency;

    public EnterpriseDataInitializer(
        UserDirectory userDirectory,
        AuditTrailService auditTrail,
        DepartmentRepository departmentRepository,
        EmployeeRepository employeeRepository,
        EmploymentEventRepository employmentEventRepository,
        CompanyRepository companyRepository,
        CompanyMembershipRepository companyMembershipRepository,
        CompanyProvisioningService companyProvisioning,
        @Value("${mamoji.bootstrap.company-name:我的公司}") String companyName,
        @Value("${mamoji.bootstrap.company-credit-code:}") String companyCreditCode,
        @Value("${mamoji.bootstrap.company-industry:未设置}") String companyIndustry,
        @Value("${mamoji.bootstrap.company-taxpayer-type:未设置}") String companyTaxpayerType,
        @Value("${mamoji.bootstrap.company-currency:CNY}") String companyCurrency
    ) {
        this.userDirectory = userDirectory;
        this.auditTrail = auditTrail;
        this.departmentRepository = departmentRepository;
        this.employeeRepository = employeeRepository;
        this.employmentEventRepository = employmentEventRepository;
        this.companyRepository = companyRepository;
        this.companyMembershipRepository = companyMembershipRepository;
        this.companyProvisioning = companyProvisioning;
        this.companyName = defaultIfBlank(companyName, "我的公司");
        this.companyCreditCode = blankToNull(companyCreditCode);
        this.companyIndustry = defaultIfBlank(companyIndustry, "未设置");
        this.companyTaxpayerType = defaultIfBlank(companyTaxpayerType, "未设置");
        this.companyCurrency = defaultIfBlank(companyCurrency, "CNY");
    }

    @PostConstruct
    void initialize() {
        if (companyRepository.existsAny()) {
            return;
        }
        UserDirectory.Entry owner = initialOwner();
        if (owner == null) {
            return;
        }

        Company company = new Company();
        company.ownerId = owner.id();
        company.name = companyName;
        company.entityType = "company";
        company.creditCode = companyCreditCode;
        company.industry = companyIndustry;
        company.taxpayerType = companyTaxpayerType;
        company.currency = companyCurrency;
        CompanyProfilePolicy.initialize(company);
        stamp(company);
        companyProvisioning.create(company);

        Department management = new Department();
        management.companyId = company.id;
        management.name = "管理层";
        management.costCenter = "MGMT";
        management.budget = BigDecimal.ZERO;
        management.status = 1;
        management.createdAt = now();
        management.updatedAt = management.createdAt;
        departmentRepository.insert(management);

        Employee founder = new Employee();
        founder.companyId = company.id;
        founder.userId = owner.id();
        founder.departmentId = management.id;
        founder.name = owner.nickname();
        founder.email = owner.email();
        founder.position = "系统管理员";
        founder.employmentType = "full_time";
        founder.status = "active";
        founder.accessRole = "founder";
        founder.accessScope = "company";
        founder.hireDate = LocalDate.now().toString();
        founder.salary = BigDecimal.ZERO;
        founder.socialInsurance = BigDecimal.ZERO;
        founder.housingFund = BigDecimal.ZERO;
        founder.taxEstimate = BigDecimal.ZERO;
        stamp(founder);
        EmployeeCompensationPolicy.initialize(founder, BigDecimal.ZERO);
        Employee insertedFounder = employeeRepository.insert(founder);
        companyMembershipRepository.synchronize(insertedFounder);

        EmploymentEvent event = new EmploymentEvent();
        event.companyId = company.id;
        event.employeeId = insertedFounder.id;
        event.type = "onboard";
        event.effectiveDate = founder.hireDate;
        event.note = "生产环境初始化管理员员工档案";
        event.operatorUserId = owner.id();
        employmentEventRepository.append(event);
        auditTrail.record(
            company.id,
            "company",
            company.id,
            "bootstrap",
            "生产环境初始化公司主体: " + company.name,
            owner.id(),
            owner.nickname()
        );
    }

    private UserDirectory.Entry initialOwner() {
        List<UserDirectory.Entry> users = userDirectory.findAll();
        return users.stream()
            .filter(user -> user.role() == 1)
            .min(Comparator.comparing(UserDirectory.Entry::id))
            .or(() -> users.stream().min(Comparator.comparing(UserDirectory.Entry::id)))
            .orElse(null);
    }

    private static void stamp(Company company) {
        String now = now();
        company.createdAt = now;
        company.updatedAt = now;
    }

    private static void stamp(Employee employee) {
        String now = now();
        employee.createdAt = now;
        employee.updatedAt = now;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String now() {
        return OffsetDateTime.now().toString();
    }
}
