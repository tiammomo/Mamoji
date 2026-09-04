package com.mamoji.bootstrap;

import com.mamoji.common.Permissions;
import com.mamoji.common.Roles;
import com.mamoji.people.application.DepartmentRepository;
import com.mamoji.people.application.EmployeeRepository;
import com.mamoji.people.application.EmploymentEventRepository;
import com.mamoji.people.domain.Department;
import com.mamoji.people.domain.Employee;
import com.mamoji.people.domain.EmployeeCompensationPolicy;
import com.mamoji.people.domain.EmploymentEvent;
import com.mamoji.platform.audit.application.AuditTrailService;
import com.mamoji.platform.identity.User;
import com.mamoji.platform.identity.account.application.LocalUserAccountRepository;
import com.mamoji.platform.identity.account.application.UserDirectory;
import com.mamoji.platform.tenant.Company;
import com.mamoji.platform.tenant.CompanyMembershipRepository;
import com.mamoji.platform.tenant.CompanyProfilePolicy;
import com.mamoji.platform.tenant.CompanyRepository;
import com.mamoji.service.CompanyProvisioningService;
import com.mamoji.service.support.PasswordHasher;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically creates the first production administrator and tenant workspace. */
@Service
public class ProductionBootstrapCommand {
    private static final String BOOTSTRAP_LOCK_KEY = "mamoji:production-bootstrap:v1";

    private final JdbcTemplate jdbc;
    private final LocalUserAccountRepository userAccounts;
    private final UserDirectory userDirectory;
    private final PasswordHasher passwordHasher;
    private final AuditTrailService auditTrail;
    private final DepartmentRepository departments;
    private final EmployeeRepository employees;
    private final EmploymentEventRepository employmentEvents;
    private final CompanyRepository companies;
    private final CompanyMembershipRepository memberships;
    private final CompanyProvisioningService companyProvisioning;

    public ProductionBootstrapCommand(
        JdbcTemplate jdbc,
        LocalUserAccountRepository userAccounts,
        UserDirectory userDirectory,
        PasswordHasher passwordHasher,
        AuditTrailService auditTrail,
        DepartmentRepository departments,
        EmployeeRepository employees,
        EmploymentEventRepository employmentEvents,
        CompanyRepository companies,
        CompanyMembershipRepository memberships,
        CompanyProvisioningService companyProvisioning
    ) {
        this.jdbc = jdbc;
        this.userAccounts = userAccounts;
        this.userDirectory = userDirectory;
        this.passwordHasher = passwordHasher;
        this.auditTrail = auditTrail;
        this.departments = departments;
        this.employees = employees;
        this.employmentEvents = employmentEvents;
        this.companies = companies;
        this.memberships = memberships;
        this.companyProvisioning = companyProvisioning;
    }

    @Transactional
    public Outcome execute(Request request) {
        acquireDatabaseLock();
        if (companies.existsAny()) {
            return Outcome.ALREADY_INITIALIZED;
        }

        UserDirectory.Entry owner = ensureOwner(request);
        Company company = company(request, owner.id());
        companyProvisioning.create(company);

        Department management = managementDepartment(company.id);
        departments.insert(management);

        Employee founder = founder(company.id, management.id, owner);
        Employee insertedFounder = employees.insert(founder);
        memberships.synchronize(insertedFounder);
        employmentEvents.append(onboardingEvent(company.id, insertedFounder.id, founder.hireDate, owner.id()));
        auditTrail.record(
            company.id,
            "company",
            company.id,
            "bootstrap",
            "生产环境初始化公司主体: " + company.name,
            owner.id(),
            owner.nickname()
        );
        return Outcome.CREATED;
    }

    private void acquireDatabaseLock() {
        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> { },
            BOOTSTRAP_LOCK_KEY
        );
    }

    private UserDirectory.Entry ensureOwner(Request request) {
        return userDirectory.findBootstrapOwner().orElseGet(() -> createOwner(request));
    }

    private UserDirectory.Entry createOwner(Request request) {
        String email = defaultIfBlank(request.adminEmail(), "test@mamoji.com")
            .trim()
            .toLowerCase(Locale.ROOT);
        String password = defaultIfBlank(request.adminPassword(), "123456");
        validateAdmin(email, password, request.passwordMinLength(), request.passwordRequireComplexity());

        User user = new User();
        user.email = email;
        user.nickname = defaultIfBlank(request.adminNickname(), "Mamoji 公司管理员");
        user.avatar = "😊|#3370ff";
        user.role = Roles.ADMIN;
        user.permissions = Permissions.ALL;
        user.passwordHash = passwordHasher.hash(password);
        String now = now();
        user.createdAt = now;
        user.updatedAt = now;
        userAccounts.insert(user);
        return new UserDirectory.Entry(
            user.id,
            user.email,
            user.nickname,
            user.avatar,
            user.familyId,
            user.role,
            user.permissions
        );
    }

    private Company company(Request request, long ownerId) {
        Company company = new Company();
        company.ownerId = ownerId;
        company.name = defaultIfBlank(request.companyName(), "我的公司");
        company.entityType = "company";
        company.creditCode = blankToNull(request.companyCreditCode());
        company.industry = defaultIfBlank(request.companyIndustry(), "未设置");
        company.taxpayerType = defaultIfBlank(request.companyTaxpayerType(), "未设置");
        company.currency = defaultIfBlank(request.companyCurrency(), "CNY");
        CompanyProfilePolicy.initialize(company);
        stamp(company);
        return company;
    }

    private Department managementDepartment(long companyId) {
        Department management = new Department();
        management.companyId = companyId;
        management.name = "管理层";
        management.costCenter = "MGMT";
        management.budget = BigDecimal.ZERO;
        management.status = 1;
        management.createdAt = now();
        management.updatedAt = management.createdAt;
        return management;
    }

    private Employee founder(long companyId, long departmentId, UserDirectory.Entry owner) {
        Employee founder = new Employee();
        founder.companyId = companyId;
        founder.userId = owner.id();
        founder.departmentId = departmentId;
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
        return founder;
    }

    private EmploymentEvent onboardingEvent(long companyId, long employeeId, String hireDate, long ownerId) {
        EmploymentEvent event = new EmploymentEvent();
        event.companyId = companyId;
        event.employeeId = employeeId;
        event.type = "onboard";
        event.effectiveDate = hireDate;
        event.note = "生产环境初始化管理员员工档案";
        event.operatorUserId = ownerId;
        return event;
    }

    private void validateAdmin(String email, String password, int configuredMinLength, boolean requireComplexity) {
        if (!email.contains("@")) {
            throw new IllegalStateException("MAMOJI_BOOTSTRAP_ADMIN_EMAIL must be a valid email in bootstrap mode");
        }
        int minLength = Math.max(8, configuredMinLength);
        if (password.length() < minLength
            || "123456".equals(password)
            || password.toLowerCase(Locale.ROOT).contains("replace-with")) {
            throw new IllegalStateException(
                "MAMOJI_BOOTSTRAP_ADMIN_PASSWORD must be replaced with a strong password in bootstrap mode"
            );
        }
        if (requireComplexity && passwordComplexityClasses(password) < 3) {
            throw new IllegalStateException(
                "MAMOJI_BOOTSTRAP_ADMIN_PASSWORD must contain at least three of lowercase, uppercase, "
                    + "digits and symbols in bootstrap mode"
            );
        }
    }

    private int passwordComplexityClasses(String password) {
        int classes = 0;
        if (password.chars().anyMatch(Character::isLowerCase)) classes++;
        if (password.chars().anyMatch(Character::isUpperCase)) classes++;
        if (password.chars().anyMatch(Character::isDigit)) classes++;
        if (password.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch))) classes++;
        return classes;
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

    public enum Outcome {
        CREATED,
        ALREADY_INITIALIZED
    }

    public record Request(
        String adminEmail,
        String adminPassword,
        String adminNickname,
        int passwordMinLength,
        boolean passwordRequireComplexity,
        String companyName,
        String companyCreditCode,
        String companyIndustry,
        String companyTaxpayerType,
        String companyCurrency
    ) {
    }
}
