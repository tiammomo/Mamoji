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
import com.mamoji.platform.tenant.EntityTransfer;
import com.mamoji.platform.tenant.EntityTransferRepository;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Owns local demo enterprise fixtures and is absent from production bootstrap contexts. */
@Component("enterpriseDataInitializer")
@ConditionalOnProperty(name = "mamoji.bootstrap.mode", havingValue = "demo", matchIfMissing = true)
@DependsOn("initialAdminDataInitializer")
public class DemoEnterpriseDataInitializer {
    private static final String DEMO_COMPANY_CREDIT_CODE = "DEMO-COMPANY-CREDIT-CODE";
    private static final int[] COMPENSATION_BENCHMARK_SALARIES = {
        3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000,
        15000, 20000, 25000, 30000, 35000, 40000, 45000, 50000
    };
    private static final String[] COMPENSATION_BENCHMARK_HIRE_DATES = {
        "2026-06-01", "2026-05-20", "2026-05-10", "2026-04-22",
        "2026-04-08", "2026-03-18", "2026-03-05", "2026-02-25",
        "2026-02-14", "2026-01-30", "2026-01-12", "2025-12-20",
        "2025-11-18", "2025-10-16", "2025-09-12", "2025-08-08"
    };

    private final JdbcTemplate jdbc;
    private final UserDirectory userDirectory;
    private final AuditTrailService auditTrail;
    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final EmploymentEventRepository employmentEventRepository;
    private final CompanyRepository companyRepository;
    private final CompanyMembershipRepository companyMembershipRepository;
    private final EntityTransferRepository entityTransferRepository;

    public DemoEnterpriseDataInitializer(
        JdbcTemplate jdbc,
        UserDirectory userDirectory,
        AuditTrailService auditTrail,
        DepartmentRepository departmentRepository,
        EmployeeRepository employeeRepository,
        EmploymentEventRepository employmentEventRepository,
        CompanyRepository companyRepository,
        CompanyMembershipRepository companyMembershipRepository,
        EntityTransferRepository entityTransferRepository
    ) {
        this.jdbc = jdbc;
        this.userDirectory = userDirectory;
        this.auditTrail = auditTrail;
        this.departmentRepository = departmentRepository;
        this.employeeRepository = employeeRepository;
        this.employmentEventRepository = employmentEventRepository;
        this.companyRepository = companyRepository;
        this.companyMembershipRepository = companyMembershipRepository;
        this.entityTransferRepository = entityTransferRepository;
    }

    @PostConstruct
    void initialize() {
        ensureSeedData();
        ensureHouseholdSubject();
        ensureCompensationBenchmarkEmployees();
        ensureEntityTransferSeed();
    }

    private Optional<UserDirectory.Entry> initialOwner() {
        List<UserDirectory.Entry> users = userDirectory.findAll();
        return users.stream()
            .filter(user -> user.role() == 1)
            .min(Comparator.comparing(UserDirectory.Entry::id))
            .or(() -> users.stream().min(Comparator.comparing(UserDirectory.Entry::id)));
    }

    private void ensureSeedData() {
        if (companyRepository.existsAny()) {
            return;
        }
        UserDirectory.Entry owner = initialOwner().orElse(null);
        if (owner == null) {
            return;
        }

        Company company = newCompany(
            owner.id(),
            "深圳市示例电商科技有限公司",
            "company",
            DEMO_COMPANY_CREDIT_CODE,
            "软件与信息技术服务",
            "小规模纳税人",
            "CNY"
        );
        insertCompany(company);
        Department management = createSeedDepartment(company.id, "管理层", "CEO", "30000");
        Department finance = createSeedDepartment(company.id, "财务行政", "FIN-ADMIN", "42000");
        Department product = createSeedDepartment(company.id, "产品研发", "RND", "120000");
        Department sales = createSeedDepartment(company.id, "市场销售", "SALES", "65000");

        Employee founder = createSeedEmployee(company.id, owner.id(), management.id, owner.nickname(), owner.email(), "13800000001",
            "创始人 / CEO", "full_time", "active", "founder", "company", "2026-01-05", null,
            "40000", "6993.99", "4800", "4550.86", "51793.99", "李女士 13800000009");
        Optional<UserDirectory.Entry> member = userDirectory.findAll().stream()
            .filter(user -> user.id() != owner.id())
            .min(Comparator.comparing(UserDirectory.Entry::id));
        member.ifPresent(user -> createSeedEmployee(company.id, user.id(), finance.id, user.nickname(), user.email(), "13800000002",
            "财务与人事负责人", "full_time", "active", "hr_admin", "company", "2026-02-10", null,
            "18000", "2700", "2160", "1200", "24060", "王先生 13800000010"));
        createSeedEmployee(company.id, null, product.id, "陈一鸣", "chen.yiming@mamoji.local", "13800000003",
            "研发负责人", "full_time", "active", "department_manager", "department", "2026-03-01", null,
            "22000", "3300", "2640", "1700", "29640", "陈女士 13800000011");
        createSeedEmployee(company.id, null, product.id, "林小北", "lin.xiaobei@mamoji.local", "13800000004",
            "产品设计师", "full_time", "probation", "employee", "self", "2026-05-20", null,
            "16000", "2400", "1920", "800", "21120", "林先生 13800000012");
        createSeedEmployee(company.id, null, sales.id, "周予安", "zhou.yuan@mamoji.local", "13800000005",
            "客户成功经理", "full_time", "onboarding", "employee", "self", "2026-06-15", null,
            "15000", "2250", "1800", "600", "19650", "周女士 13800000013");
        createSeedEmployee(company.id, null, sales.id, "运营专员", "operations.specialist@mamoji.local", "13800000007",
            "运营专员", "full_time", "active", "employee", "self", "2026-06-07", null,
            "5000", "1287.26", "600", "0", "6887.26", "赵女士 13800000015");
        createSeedEmployee(company.id, null, sales.id, "吴青", "wu.qing@mamoji.local", "13800000006",
            "市场运营", "full_time", "departed", "viewer", "self", "2026-02-15", "2026-06-03",
            "14000", "2100", "1680", "500", "18280", "吴先生 13800000014");

        createSeedEmploymentEvent(company.id, founder.id, "onboard", founder.hireDate, "公司创始人账号初始化", owner.id());
        employeeRepository.findByCompany(company.id, false).stream()
            .filter(employee -> employee.companyId == company.id && employee.id != founder.id)
            .forEach(employee -> createSeedEmploymentEvent(
                company.id,
                employee.id,
                "onboard",
                employee.hireDate,
                "演示员工入职",
                owner.id()
            ));
        employeeRepository.findByCompany(company.id, false).stream()
            .filter(employee -> employee.companyId == company.id && "departed".equals(employee.status))
            .forEach(employee -> createSeedEmploymentEvent(
                company.id,
                employee.id,
                "offboard",
                employee.leaveDate,
                "演示员工离职交接完成",
                owner.id()
            ));

    }

    private void ensureCompensationBenchmarkEmployees() {
        companyRepository.findAll().stream()
            .filter(this::isDemoCompany)
            .forEach(this::ensureCompensationBenchmarkEmployees);
    }

    private void ensureCompensationBenchmarkEmployees(Company company) {
        Department benchmarkDepartment = departmentRepository.findByCompany(company.id).stream()
            .filter(department -> "薪酬样例".equals(department.name))
            .min(Comparator.comparing(department -> department.id))
            .orElseGet(() -> createSeedDepartment(company.id, "薪酬样例", "PAY-SAMPLE", "780000"));
        long operatorUserId = userDirectory.findById(company.ownerId).isPresent()
            ? company.ownerId
            : userDirectory.findAll().stream()
                .min(Comparator.comparing(UserDirectory.Entry::id))
                .map(UserDirectory.Entry::id)
                .orElse(0L);

        for (int i = 0; i < COMPENSATION_BENCHMARK_SALARIES.length; i += 1) {
            int salary = COMPENSATION_BENCHMARK_SALARIES[i];
            String email = String.format(Locale.ROOT, "salary.sample.%05d@mamoji.local", salary);
            boolean exists = employeeRepository.existsByCompanyAndEmail(company.id, email);
            if (exists) {
                continue;
            }
            Employee benchmarkEmployee = createSeedEmployee(
                company.id,
                null,
                benchmarkDepartment.id,
                "薪酬样例 " + salary + "元",
                email,
                String.format(Locale.ROOT, "139%08d", salary),
                "工资档位 " + salary + " 元",
                "full_time",
                "active",
                "employee",
                "self",
                COMPENSATION_BENCHMARK_HIRE_DATES[i],
                null,
                String.valueOf(salary),
                "0",
                "0",
                "0",
                "0",
                "样例联系人 13900000000"
            );
            benchmarkEmployee.employeeNo = String.format(Locale.ROOT, "PAY-SAMPLE-%05d", salary);
            benchmarkEmployee.jobLevel = "薪酬测算样例";
            benchmarkEmployee.workLocation = "深圳";
            benchmarkEmployee.resumeSummary = salary + " 元工资档位样例，用于观察个人到账、公司缴费和公司总成本。";
            benchmarkEmployee.materialStatus = "verified";
            employeeRepository.update(benchmarkEmployee);
            if (operatorUserId > 0) {
                createSeedEmploymentEvent(
                    company.id,
                    benchmarkEmployee.id,
                    "onboard",
                    benchmarkEmployee.hireDate,
                    "薪酬档位样例入职",
                    operatorUserId
                );
            }
        }
        retainOnlyCompensationBenchmarkEmployees(company);
    }

    private void retainOnlyCompensationBenchmarkEmployees(Company company) {
        List<Long> employeeIdsToRemove = employeeRepository.findByCompany(company.id, false).stream()
            .filter(employee -> employee.companyId == company.id)
            .filter(employee -> !isCompensationBenchmarkEmployee(employee))
            .map(employee -> employee.id)
            .toList();
        if (employeeIdsToRemove.isEmpty()) {
            return;
        }
        jdbc.update("DELETE FROM payroll_run_items WHERE run_id IN (SELECT id FROM payroll_runs WHERE company_id = ?)", company.id);
        jdbc.update("DELETE FROM payroll_runs WHERE company_id = ?", company.id);
        employeeIdsToRemove.forEach(employeeId -> {
            employmentEventRepository.deleteByEmployeeForDemoReset(employeeId);
            employeeRepository.deleteForDemoReset(employeeId);
        });
    }

    private boolean isDemoCompany(Company company) {
        return DEMO_COMPANY_CREDIT_CODE.equals(company.creditCode) || "深圳市示例电商科技有限公司".equals(company.name);
    }

    private boolean isCompensationBenchmarkEmployee(Employee employee) {
        return employee.email != null && employee.email.toLowerCase(Locale.ROOT).startsWith("salary.sample.");
    }

    private void ensureHouseholdSubject() {
        boolean hasHousehold = companyRepository.findAll().stream()
            .anyMatch(company -> "household".equals(company.entityType));
        if (hasHousehold) {
            return;
        }
        UserDirectory.Entry owner = initialOwner().orElse(null);
        if (owner == null) {
            return;
        }
        Company household = newCompany(
            owner.id(),
            "演示家庭资产主体",
            "household",
            null,
            "家庭资产管理",
            "非经营主体",
            "CNY"
        );
        household.province = "广东省";
        household.city = "深圳市";
        household.operatingRegion = CompanyProfilePolicy.regionLabel(household);
        household.policyProfileKey = "CN-HOUSEHOLD-ASSET-PROFILE";
        insertCompany(household);
    }

    private void ensureEntityTransferSeed() {
        List<Company> companies = companyRepository.findAll();
        Optional<Company> company = companies.stream()
            .filter(candidate -> "company".equals(candidate.entityType))
            .min(Comparator.comparing(candidate -> candidate.id));
        Optional<Company> household = companies.stream()
            .filter(candidate -> "household".equals(candidate.entityType))
            .min(Comparator.comparing(candidate -> candidate.id));
        if (company.isEmpty() || household.isEmpty()) {
            return;
        }
        long companyId = company.get().id;
        long householdId = household.get().id;
        if (entityTransferRepository.existsBetween(companyId, householdId)) {
            return;
        }
        UserDirectory.Entry owner = initialOwner().orElse(null);
        if (owner == null) {
            return;
        }
        String currency = isBlank(company.get().currency) ? "CNY" : company.get().currency;
        seedEntityTransfer(householdId, companyId, "shareholder_advance", "50000", currency, "2026-02-01",
            "家庭资金垫付公司启动备用金", "recorded", owner.id());
        seedEntityTransfer(companyId, householdId, "advance_repayment", "12000", currency, "2026-04-15",
            "公司归还部分家庭垫资", "recorded", owner.id());
        seedEntityTransfer(householdId, companyId, "expense_reimbursement", "2680", currency, "2026-05-08",
            "家庭账户代垫 SaaS 订阅和办公采购", "recorded", owner.id());
        seedEntityTransfer(companyId, householdId, "reimbursement_payment", "2680", currency, "2026-05-20",
            "公司报销家庭代垫支出", "recorded", owner.id());
    }

    private Company newCompany(
        long ownerId,
        String name,
        String entityType,
        String creditCode,
        String industry,
        String taxpayerType,
        String currency
    ) {
        Company company = new Company();
        company.ownerId = ownerId;
        company.name = name;
        company.entityType = entityType;
        company.creditCode = creditCode;
        company.industry = industry;
        company.taxpayerType = taxpayerType;
        company.currency = currency;
        CompanyProfilePolicy.initialize(company);
        stamp(company);
        return company;
    }

    private void insertCompany(Company company) {
        companyRepository.insert(company);
        companyMembershipRepository.ensureOwner(company);
    }

    private Department createSeedDepartment(long companyId, String name, String costCenter, String budget) {
        Department department = new Department();
        department.companyId = companyId;
        department.name = name;
        department.costCenter = costCenter == null ? "" : costCenter;
        department.budget = money(budget);
        department.status = 1;
        String now = now();
        department.createdAt = now;
        department.updatedAt = now;
        return departmentRepository.insert(department);
    }

    private Employee createSeedEmployee(
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
        employee.salary = money(salary);
        employee.socialInsurance = money(socialInsurance);
        employee.housingFund = money(housingFund);
        employee.taxEstimate = money(taxEstimate);
        employee.emergencyContact = emergencyContact;
        stamp(employee);
        EmployeeCompensationPolicy.initialize(employee, money(monthlyCost));
        Employee inserted = employeeRepository.insert(employee);
        companyMembershipRepository.synchronize(inserted);
        return inserted;
    }

    private EmploymentEvent createSeedEmploymentEvent(
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
        event.note = note == null ? "" : note;
        event.operatorUserId = operatorUserId;
        return employmentEventRepository.append(event);
    }

    private EntityTransfer seedEntityTransfer(
        long fromEntityId,
        long toEntityId,
        String transferType,
        String amount,
        String currency,
        String transferDate,
        String note,
        String status,
        long operatorUserId
    ) {
        EntityTransfer transfer = new EntityTransfer();
        transfer.fromEntityId = fromEntityId;
        transfer.toEntityId = toEntityId;
        transfer.transferType = transferType == null || transferType.isBlank() ? "inter_entity_transfer" : transferType;
        transfer.amount = money(amount);
        transfer.currency = currency == null || currency.isBlank() ? "CNY" : currency;
        transfer.transferDate = transferDate == null || transferDate.isBlank() ? LocalDate.now().toString() : transferDate;
        transfer.note = note;
        transfer.status = status == null || status.isBlank() ? "recorded" : status;
        transfer.operatorUserId = operatorUserId;
        return entityTransferRepository.append(transfer);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static BigDecimal money(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(value));
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

    private static String now() {
        return OffsetDateTime.now().toString();
    }
}
