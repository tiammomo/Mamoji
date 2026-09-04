package com.mamoji.repository;

import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.Employee;
import com.mamoji.domain.Models.EmployeeCertificate;
import com.mamoji.domain.Models.EmployeeExperience;
import com.mamoji.domain.Models.EmploymentEvent;
import com.mamoji.domain.Models.EntityTransfer;
import com.mamoji.domain.Models.SocialInsuranceItem;
import com.mamoji.platform.audit.application.AuditLogRepository;
import com.mamoji.platform.audit.domain.AuditEvent;
import com.mamoji.platform.audit.domain.AuditLog;
import com.mamoji.platform.identity.account.application.UserDirectory;
import com.mamoji.people.application.DepartmentRepository;
import com.mamoji.people.domain.Department;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

@Component
public class EnterpriseStore {
    public final Map<Long, Company> companies = new ConcurrentHashMap<>();
    public final Map<Long, Employee> employees = new ConcurrentHashMap<>();
    public final Map<Long, EntityTransfer> entityTransfers = new ConcurrentHashMap<>();
    public final Map<Long, EmploymentEvent> employmentEvents = new ConcurrentHashMap<>();
    private static final String DEFAULT_SOCIAL_INSURANCE_REGION = "深圳";
    private static final String DEFAULT_POLICY_PROFILE = "CN-DEFAULT-DEMO-POLICY";
    private static final String LEGACY_SHENZHEN_POLICY_PROFILE = "CN-GD-SZ-DEMO-POLICY";
    private static final String SHENZHEN_STARTUP_POLICY_PROFILE = "CN-GD-SZ-STARTUP-LITE";
    private static final String DEFAULT_HUKOU_TYPE = "non_local";
    private static final String DEFAULT_MEDICAL_TIER = "tier1";
    private static final BigDecimal SHENZHEN_PENSION_MIN_BASE = new BigDecimal("4775");
    private static final BigDecimal SHENZHEN_PENSION_MAX_BASE = new BigDecimal("27549");
    private static final BigDecimal SHENZHEN_MEDICAL_MIN_BASE = new BigDecimal("6727");
    private static final BigDecimal SHENZHEN_MEDICAL_MAX_BASE = new BigDecimal("33633");
    private static final BigDecimal SHENZHEN_UNEMPLOYMENT_MIN_BASE = new BigDecimal("2520");
    private static final BigDecimal SHENZHEN_UNEMPLOYMENT_MAX_BASE = new BigDecimal("44265");
    private static final BigDecimal SHENZHEN_HOUSING_FUND_MIN_BASE = new BigDecimal("2520");
    private static final BigDecimal SHENZHEN_HOUSING_FUND_MAX_BASE = new BigDecimal("44265");
    private static final BigDecimal DEFAULT_PENSION_PERSONAL_RATE = new BigDecimal("8");
    private static final BigDecimal DEFAULT_PENSION_COMPANY_RATE = new BigDecimal("16");
    private static final BigDecimal DEFAULT_LOCAL_SUPPLEMENT_PENSION_COMPANY_RATE = new BigDecimal("1");
    private static final BigDecimal DEFAULT_MEDICAL_TIER1_PERSONAL_RATE = new BigDecimal("2");
    private static final BigDecimal DEFAULT_MEDICAL_TIER1_COMPANY_RATE = new BigDecimal("6");
    private static final BigDecimal DEFAULT_MEDICAL_TIER2_PERSONAL_RATE = new BigDecimal("0.5");
    private static final BigDecimal DEFAULT_MEDICAL_TIER2_COMPANY_RATE = new BigDecimal("1.5");
    private static final BigDecimal DEFAULT_MATERNITY_COMPANY_RATE = new BigDecimal("0.5");
    private static final BigDecimal DEFAULT_UNEMPLOYMENT_PERSONAL_RATE = new BigDecimal("0.2");
    private static final BigDecimal DEFAULT_UNEMPLOYMENT_COMPANY_RATE = new BigDecimal("0.8");
    private static final BigDecimal DEFAULT_WORK_INJURY_COMPANY_RATE = new BigDecimal("0.2");
    private static final BigDecimal MAX_WORK_INJURY_COMPANY_RATE = new BigDecimal("1.4");
    private static final BigDecimal MIN_HOUSING_FUND_RATE = new BigDecimal("5");
    private static final BigDecimal DEFAULT_HOUSING_FUND_RATE = new BigDecimal("12");
    private static final BigDecimal MAX_HOUSING_FUND_RATE = new BigDecimal("12");
    private static final BigDecimal OVERTIME_MONTHLY_PAID_DAYS = new BigDecimal("21.75");
    private static final BigDecimal STANDARD_DAILY_WORK_HOURS = new BigDecimal("8");
    private static final BigDecimal WEEKDAY_OVERTIME_RATE = new BigDecimal("1.5");
    private static final BigDecimal REST_DAY_OVERTIME_RATE = new BigDecimal("2");
    private static final BigDecimal HOLIDAY_OVERTIME_RATE = new BigDecimal("3");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
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
    private final AuditLogRepository auditLogRepository;
    private final DepartmentRepository departmentRepository;
    private final String bootstrapMode;
    private final String bootstrapCompanyName;
    private final String bootstrapCompanyCreditCode;
    private final String bootstrapCompanyIndustry;
    private final String bootstrapCompanyTaxpayerType;
    private final String bootstrapCompanyCurrency;

    public EnterpriseStore(
        JdbcTemplate jdbc,
        UserDirectory userDirectory,
        AuditLogRepository auditLogRepository,
        DepartmentRepository departmentRepository,
        @Value("${mamoji.bootstrap.mode:demo}") String bootstrapMode,
        @Value("${mamoji.bootstrap.company-name:我的公司}") String bootstrapCompanyName,
        @Value("${mamoji.bootstrap.company-credit-code:}") String bootstrapCompanyCreditCode,
        @Value("${mamoji.bootstrap.company-industry:未设置}") String bootstrapCompanyIndustry,
        @Value("${mamoji.bootstrap.company-taxpayer-type:未设置}") String bootstrapCompanyTaxpayerType,
        @Value("${mamoji.bootstrap.company-currency:CNY}") String bootstrapCompanyCurrency
    ) {
        this.jdbc = jdbc;
        this.userDirectory = userDirectory;
        this.auditLogRepository = auditLogRepository;
        this.departmentRepository = departmentRepository;
        this.bootstrapMode = defaultIfBlank(bootstrapMode, "demo").toLowerCase(Locale.ROOT);
        this.bootstrapCompanyName = defaultIfBlank(bootstrapCompanyName, "我的公司");
        this.bootstrapCompanyCreditCode = blankToNull(bootstrapCompanyCreditCode);
        this.bootstrapCompanyIndustry = defaultIfBlank(bootstrapCompanyIndustry, "未设置");
        this.bootstrapCompanyTaxpayerType = defaultIfBlank(bootstrapCompanyTaxpayerType, "未设置");
        this.bootstrapCompanyCurrency = defaultIfBlank(bootstrapCompanyCurrency, "CNY");
    }

    @PostConstruct
    void initialize() {
        loadAll();
        ensureInitialEnterpriseData();
        ensureCompanyPolicyDefaults();
        if (!isBootstrapMode()) {
            ensureHouseholdSubject();
            ensureCompensationBenchmarkEmployees();
            ensureEntityTransferSeed();
        }
        ensureEmployeePayrollDefaults();
        ensureAccessDefaults();
        attachDepartmentNames();
    }

    private void loadAll() {
        companies.clear();
        employees.clear();
        entityTransfers.clear();
        employmentEvents.clear();

        forEachRow("SELECT * FROM companies", rs -> companies.put(rs.getLong("id"), mapCompany(rs)));
        forEachRow("SELECT * FROM employees", rs -> employees.put(rs.getLong("id"), mapEmployee(rs)));
        forEachRow("SELECT * FROM entity_transfers", rs -> entityTransfers.put(rs.getLong("id"), mapEntityTransfer(rs)));
        forEachRow("SELECT * FROM employment_events", rs -> employmentEvents.put(rs.getLong("id"), mapEmploymentEvent(rs)));
    }

    /** Reload the process-local compatibility view after a controlled restore. */
    public synchronized void reloadFromDatabase() {
        loadAll();
        attachDepartmentNames();
    }

    private void ensureInitialEnterpriseData() {
        if (!companies.isEmpty()) {
            return;
        }
        if (isBootstrapMode()) {
            ensureBootstrapEnterpriseData();
            return;
        }
        ensureSeedData();
    }

    private Optional<UserDirectory.Entry> initialOwner() {
        List<UserDirectory.Entry> users = userDirectory.findAll();
        return users.stream()
            .filter(user -> user.role() == 1)
            .min(Comparator.comparing(UserDirectory.Entry::id))
            .or(() -> users.stream().min(Comparator.comparing(UserDirectory.Entry::id)));
    }

    private void ensureBootstrapEnterpriseData() {
        UserDirectory.Entry owner = initialOwner().orElse(null);
        if (owner == null) {
            return;
        }
        Company company = company(
            owner.id(),
            bootstrapCompanyName,
            bootstrapCompanyCreditCode,
            bootstrapCompanyIndustry,
            bootstrapCompanyTaxpayerType,
            bootstrapCompanyCurrency
        );
        company.country = "中国";
        company.operatingRegion = regionLabel(company);
        company.policyProfileKey = defaultPolicyProfileKey(company);
        saveCompany(company);

        Department management = createSeedDepartment(company.id, "管理层", "MGMT", "0");
        Employee founder = employee(
            company.id,
            owner.id(),
            management.id,
            owner.nickname(),
            owner.email(),
            null,
            "系统管理员",
            "full_time",
            "active",
            "founder",
            "company",
            LocalDate.now().toString(),
            null,
            "0",
            "0",
            "0",
            "0",
            "0",
            null
        );
        event(company.id, founder.id, "onboard", founder.hireDate, "生产环境初始化管理员员工档案", owner.id());
        auditLog(company.id, "company", company.id, "bootstrap", "生产环境初始化公司主体: " + company.name, owner.id(), owner.nickname());
    }

    private void ensureSeedData() {
        if (!companies.isEmpty()) {
            return;
        }
        UserDirectory.Entry owner = initialOwner().orElse(null);
        if (owner == null) {
            return;
        }

        Company company = company(owner.id(), "深圳市示例电商科技有限公司", DEMO_COMPANY_CREDIT_CODE, "软件与信息技术服务", "小规模纳税人", "CNY");
        Department management = createSeedDepartment(company.id, "管理层", "CEO", "30000");
        Department finance = createSeedDepartment(company.id, "财务行政", "FIN-ADMIN", "42000");
        Department product = createSeedDepartment(company.id, "产品研发", "RND", "120000");
        Department sales = createSeedDepartment(company.id, "市场销售", "SALES", "65000");

        Employee founder = employee(company.id, owner.id(), management.id, owner.nickname(), owner.email(), "13800000001",
            "创始人 / CEO", "full_time", "active", "founder", "company", "2026-01-05", null,
            "40000", "6993.99", "4800", "4550.86", "51793.99", "李女士 13800000009");
        Optional<UserDirectory.Entry> member = userDirectory.findAll().stream()
            .filter(user -> user.id() != owner.id())
            .min(Comparator.comparing(UserDirectory.Entry::id));
        member.ifPresent(user -> employee(company.id, user.id(), finance.id, user.nickname(), user.email(), "13800000002",
            "财务与人事负责人", "full_time", "active", "hr_admin", "company", "2026-02-10", null,
            "18000", "2700", "2160", "1200", "24060", "王先生 13800000010"));
        employee(company.id, null, product.id, "陈一鸣", "chen.yiming@mamoji.local", "13800000003",
            "研发负责人", "full_time", "active", "department_manager", "department", "2026-03-01", null,
            "22000", "3300", "2640", "1700", "29640", "陈女士 13800000011");
        employee(company.id, null, product.id, "林小北", "lin.xiaobei@mamoji.local", "13800000004",
            "产品设计师", "probation", "probation", "employee", "self", "2026-05-20", null,
            "16000", "2400", "1920", "800", "21120", "林先生 13800000012");
        employee(company.id, null, sales.id, "周予安", "zhou.yuan@mamoji.local", "13800000005",
            "客户成功经理", "full_time", "onboarding", "employee", "self", "2026-06-15", null,
            "15000", "2250", "1800", "600", "19650", "周女士 13800000013");
        employee(company.id, null, sales.id, "运营专员", "operations.specialist@mamoji.local", "13800000007",
            "运营专员", "full_time", "active", "employee", "self", "2026-06-07", null,
            "5000", "1287.26", "600", "0", "6887.26", "赵女士 13800000015");
        employee(company.id, null, sales.id, "吴青", "wu.qing@mamoji.local", "13800000006",
            "市场运营", "full_time", "departed", "viewer", "self", "2026-02-15", "2026-06-03",
            "14000", "2100", "1680", "500", "18280", "吴先生 13800000014");

        event(company.id, founder.id, "onboard", founder.hireDate, "公司创始人账号初始化", owner.id());
        employees.values().stream()
            .filter(employee -> employee.companyId == company.id && employee.id != founder.id)
            .forEach(employee -> event(company.id, employee.id, "onboard", employee.hireDate, "演示员工入职", owner.id()));
        employees.values().stream()
            .filter(employee -> employee.companyId == company.id && "departed".equals(employee.status))
            .forEach(employee -> event(company.id, employee.id, "offboard", employee.leaveDate, "演示员工离职交接完成", owner.id()));

    }

    private void ensureCompensationBenchmarkEmployees() {
        companies.values().stream()
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
            boolean exists = employees.values().stream()
                .anyMatch(employee -> employee.companyId == company.id && email.equalsIgnoreCase(employee.email));
            if (exists) {
                continue;
            }
            Employee benchmarkEmployee = employee(
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
            benchmarkEmployee.materialStatus = "complete";
            saveEmployee(benchmarkEmployee);
            if (operatorUserId > 0) {
                event(company.id, benchmarkEmployee.id, "onboard", benchmarkEmployee.hireDate, "薪酬档位样例入职", operatorUserId);
            }
        }
        retainOnlyCompensationBenchmarkEmployees(company);
    }

    private void retainOnlyCompensationBenchmarkEmployees(Company company) {
        List<Long> employeeIdsToRemove = employees.values().stream()
            .filter(employee -> employee.companyId == company.id)
            .filter(employee -> !isCompensationBenchmarkEmployee(employee))
            .map(employee -> employee.id)
            .toList();
        if (employeeIdsToRemove.isEmpty()) {
            return;
        }
        jdbc.update("DELETE FROM payroll_run_items WHERE run_id IN (SELECT id FROM payroll_runs WHERE company_id = ?)", company.id);
        jdbc.update("DELETE FROM payroll_runs WHERE company_id = ?", company.id);
        employeeIdsToRemove.forEach(this::deleteEmployee);
    }

    private boolean isDemoCompany(Company company) {
        return DEMO_COMPANY_CREDIT_CODE.equals(company.creditCode) || "深圳市示例电商科技有限公司".equals(company.name);
    }

    private boolean isCompensationBenchmarkEmployee(Employee employee) {
        return employee.email != null && employee.email.toLowerCase(Locale.ROOT).startsWith("salary.sample.");
    }

    private void ensureHouseholdSubject() {
        boolean hasHousehold = companies.values().stream().anyMatch(company -> "household".equals(company.entityType));
        if (hasHousehold) {
            return;
        }
        UserDirectory.Entry owner = initialOwner().orElse(null);
        if (owner == null) {
            return;
        }
        Company household = company(owner.id(), "演示家庭资产主体", "household", null, "家庭资产管理", "非经营主体", "CNY");
        household.province = "广东省";
        household.city = "深圳市";
        household.operatingRegion = regionLabel(household);
        household.policyProfileKey = "CN-HOUSEHOLD-ASSET-PROFILE";
        household.updatedAt = InMemoryStore.now();
        saveCompany(household);
    }

    private void ensureEntityTransferSeed() {
        Optional<Company> company = companies.values().stream()
            .filter(candidate -> "company".equals(candidate.entityType))
            .min(Comparator.comparing(candidate -> candidate.id));
        Optional<Company> household = companies.values().stream()
            .filter(candidate -> "household".equals(candidate.entityType))
            .min(Comparator.comparing(candidate -> candidate.id));
        if (company.isEmpty() || household.isEmpty()) {
            return;
        }
        long companyId = company.get().id;
        long householdId = household.get().id;
        boolean hasPairTransfer = entityTransfers.values().stream().anyMatch(transfer ->
            (transfer.fromEntityId == companyId && transfer.toEntityId == householdId)
                || (transfer.fromEntityId == householdId && transfer.toEntityId == companyId));
        if (hasPairTransfer) {
            return;
        }
        UserDirectory.Entry owner = initialOwner().orElse(null);
        if (owner == null) {
            return;
        }
        String currency = isBlank(company.get().currency) ? "CNY" : company.get().currency;
        entityTransfer(householdId, companyId, "shareholder_advance", "50000", currency, "2026-02-01",
            "家庭资金垫付公司启动备用金", "recorded", owner.id());
        entityTransfer(companyId, householdId, "advance_repayment", "12000", currency, "2026-04-15",
            "公司归还部分家庭垫资", "recorded", owner.id());
        entityTransfer(householdId, companyId, "expense_reimbursement", "2680", currency, "2026-05-08",
            "家庭账户代垫 SaaS 订阅和办公采购", "recorded", owner.id());
        entityTransfer(companyId, householdId, "reimbursement_payment", "2680", currency, "2026-05-20",
            "公司报销家庭代垫支出", "recorded", owner.id());
    }

    private void ensureCompanyPolicyDefaults() {
        companies.values().forEach(company -> {
            boolean updated = false;
            if (isBlank(company.entityType)) {
                company.entityType = "company";
                updated = true;
            }
            if (isBlank(company.country)) {
                company.country = "中国";
                updated = true;
            }
            if (isBlank(company.province) && company.name != null && company.name.contains("深圳")) {
                company.province = "广东省";
                updated = true;
            }
            if (isBlank(company.city) && company.name != null && company.name.contains("深圳")) {
                company.city = "深圳市";
                updated = true;
            }
            if (isBlank(company.operatingRegion)) {
                company.operatingRegion = regionLabel(company);
                updated = true;
            }
            if (company.city != null && company.city.contains("深圳")
                && (DEFAULT_POLICY_PROFILE.equals(company.policyProfileKey) || LEGACY_SHENZHEN_POLICY_PROFILE.equals(company.policyProfileKey))) {
                company.policyProfileKey = SHENZHEN_STARTUP_POLICY_PROFILE;
                updated = true;
            } else if (isBlank(company.policyProfileKey)) {
                company.policyProfileKey = defaultPolicyProfileKey(company);
                updated = true;
            }
            if (company.fiscalYearStartMonth < 1 || company.fiscalYearStartMonth > 12) {
                company.fiscalYearStartMonth = 1;
                updated = true;
            }
            if (updated) {
                company.updatedAt = InMemoryStore.now();
                saveCompany(company);
            }
        });
    }

    private void ensureAccessDefaults() {
        Map<Long, UserDirectory.Entry> usersById = userDirectory.findAll().stream()
            .collect(java.util.stream.Collectors.toMap(UserDirectory.Entry::id, user -> user));
        employees.values().forEach(employee -> {
            String role = employee.accessRole == null || employee.accessRole.isBlank() ? "employee" : employee.accessRole;
            String scope = employee.accessScope == null || employee.accessScope.isBlank() ? "self" : employee.accessScope;
            Optional<UserDirectory.Entry> user = Optional.ofNullable(employee.userId).map(usersById::get);
            if (user.map(candidate -> candidate.role() == 1).orElse(false)) {
                role = "founder";
                scope = "company";
            } else if (employee.position != null && employee.position.contains("财务")) {
                role = "finance_admin";
                scope = "company";
            } else if (employee.position != null && employee.position.contains("人事")) {
                role = "hr_admin";
                scope = "company";
            } else if (employee.position != null && (employee.position.contains("负责人") || employee.position.contains("经理"))) {
                role = "department_manager";
                scope = "department";
            }
            if ("departed".equals(employee.status)) {
                role = "viewer";
                scope = "self";
            }
            if (!role.equals(employee.accessRole) || !scope.equals(employee.accessScope)) {
                employee.accessRole = role;
                employee.accessScope = scope;
                employee.updatedAt = InMemoryStore.now();
                saveEmployee(employee);
            }
        });
    }

    private void ensureEmployeePayrollDefaults() {
        employees.values().forEach(employee -> {
            boolean updated = hydrateEmployeePayroll(employee);
            if (updated) {
                employee.updatedAt = InMemoryStore.now();
            }
            saveEmployee(employee);
        });
    }

    public List<Company> sortedCompanies() {
        return jdbc.query("SELECT * FROM companies ORDER BY id", (rs, rowNum) -> mapCompany(rs));
    }

    public List<Employee> sortedEmployees(long companyId) {
        return sortedEmployees(companyId, true);
    }

    public List<Employee> sortedEmployees(long companyId, boolean includeProfileDetails) {
        List<Employee> result = jdbc.query("""
            SELECT employee.*, department.name AS resolved_department_name
            FROM employees employee
            LEFT JOIN departments department ON department.id = employee.department_id
            WHERE employee.company_id = ?
            ORDER BY CASE WHEN employee.status = 'departed' THEN 1 ELSE 0 END, employee.id
            """, (rs, rowNum) -> {
                Employee employee = mapEmployee(rs);
                employee.departmentName = rs.getString("resolved_department_name");
                return employee;
            }, companyId);
        if (includeProfileDetails) {
            attachEmployeeProfileDetails(companyId, result);
        }
        return result;
    }

    public List<EmploymentEvent> sortedEmploymentEvents(long companyId) {
        return jdbc.query(
            "SELECT * FROM employment_events WHERE company_id = ? ORDER BY effective_date DESC, id",
            (rs, rowNum) -> mapEmploymentEvent(rs),
            companyId
        );
    }

    public List<Employee> allEmployees() {
        return jdbc.query(
            "SELECT * FROM employees ORDER BY company_id, id",
            (rs, rowNum) -> mapEmployee(rs)
        );
    }

    public List<EntityTransfer> sortedEntityTransfers(List<Long> accessibleEntityIds, Long entityId) {
        Set<Long> accessible = new HashSet<>(accessibleEntityIds);
        return jdbc.query("""
            SELECT transfer.*, source.name AS resolved_from_name, target.name AS resolved_to_name
            FROM entity_transfers transfer
            LEFT JOIN companies source ON source.id = transfer.from_entity_id
            LEFT JOIN companies target ON target.id = transfer.to_entity_id
            ORDER BY transfer.transfer_date DESC, transfer.id
            """, (rs, rowNum) -> {
                EntityTransfer transfer = mapEntityTransfer(rs);
                transfer.fromEntityName = rs.getString("resolved_from_name");
                transfer.toEntityName = rs.getString("resolved_to_name");
                return transfer;
            }).stream()
            .filter(transfer -> accessible.contains(transfer.fromEntityId) || accessible.contains(transfer.toEntityId))
            .filter(transfer -> entityId == null || transfer.fromEntityId == entityId || transfer.toEntityId == entityId)
            .toList();
    }

    public List<AuditLog> sortedAuditLogs(long companyId, String entityType, long entityId) {
        return auditLogRepository.findByEntity(companyId, entityType, entityId);
    }

    public boolean hasAuditLogEntityType(String entityType) {
        return auditLogRepository.existsByEntityType(entityType);
    }

    public Optional<Company> findCompany(long id) {
        return jdbc.query("SELECT * FROM companies WHERE id = ?", (rs, rowNum) -> mapCompany(rs), id).stream().findFirst();
    }

    public Optional<Employee> findEmployee(long id) {
        return jdbc.query("""
            SELECT employee.*, department.name AS resolved_department_name
            FROM employees employee
            LEFT JOIN departments department ON department.id = employee.department_id
            WHERE employee.id = ?
            """, (rs, rowNum) -> {
                Employee employee = mapEmployee(rs);
                employee.departmentName = rs.getString("resolved_department_name");
                return employee;
            }, id).stream().findFirst();
    }

    public Optional<Employee> findActiveEmployeeByUser(long userId, long companyId) {
        return jdbc.query("""
            SELECT employee.*, department.name AS resolved_department_name
            FROM employees employee
            LEFT JOIN departments department ON department.id = employee.department_id
            WHERE employee.user_id = ? AND employee.company_id = ? AND employee.status <> 'departed'
            ORDER BY employee.id
            LIMIT 1
            """, (rs, rowNum) -> {
                Employee employee = mapEmployee(rs);
                employee.departmentName = rs.getString("resolved_department_name");
                return employee;
            }, userId, companyId).stream().findFirst();
    }

    public Company company(long ownerId, String name, String creditCode, String industry, String taxpayerType, String currency) {
        return company(ownerId, name, "company", creditCode, industry, taxpayerType, currency);
    }

    public Company company(long ownerId, String name, String entityType, String creditCode, String industry, String taxpayerType, String currency) {
        Company company = new Company();
        company.ownerId = ownerId;
        company.name = name;
        company.entityType = entityType == null || entityType.isBlank() ? "company" : entityType;
        company.creditCode = creditCode;
        company.industry = industry;
        company.taxpayerType = taxpayerType;
        company.currency = currency == null ? "CNY" : currency;
        company.country = "中国";
        company.province = name != null && name.contains("深圳") ? "广东省" : "";
        company.city = name != null && name.contains("深圳") ? "深圳市" : "";
        company.district = "";
        company.registeredAddress = null;
        company.operatingRegion = regionLabel(company);
        company.taxAuthority = null;
        company.policyProfileKey = defaultPolicyProfileKey(company);
        company.fiscalYearStartMonth = 1;
        stamp(company);
        company.id = insert("""
            INSERT INTO companies (
                name, entity_type, credit_code, industry, taxpayer_type, currency, country, province, city, district,
                registered_address, operating_region, tax_authority, policy_profile_key, fiscal_year_start_month,
                owner_id, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> {
            ps.setString(1, company.name);
            ps.setString(2, company.entityType);
            ps.setString(3, company.creditCode);
            ps.setString(4, company.industry);
            ps.setString(5, company.taxpayerType);
            ps.setString(6, company.currency);
            ps.setString(7, company.country);
            ps.setString(8, company.province);
            ps.setString(9, company.city);
            ps.setString(10, company.district);
            ps.setString(11, company.registeredAddress);
            ps.setString(12, company.operatingRegion);
            ps.setString(13, company.taxAuthority);
            ps.setString(14, company.policyProfileKey);
            ps.setInt(15, company.fiscalYearStartMonth);
            ps.setLong(16, company.ownerId);
            ps.setString(17, company.createdAt);
            ps.setString(18, company.updatedAt);
        });
        companies.put(company.id, company);
        return company;
    }

    public void saveCompany(Company company) {
        companies.put(company.id, company);
        jdbc.update("""
            UPDATE companies SET name = ?, entity_type = ?, credit_code = ?, industry = ?, taxpayer_type = ?, currency = ?,
                country = ?, province = ?, city = ?, district = ?, registered_address = ?, operating_region = ?,
                tax_authority = ?, policy_profile_key = ?, fiscal_year_start_month = ?, owner_id = ?, updated_at = ?
            WHERE id = ?
            """, company.name, company.entityType, company.creditCode, company.industry, company.taxpayerType, company.currency,
            company.country, company.province, company.city, company.district, company.registeredAddress, company.operatingRegion,
            company.taxAuthority, company.policyProfileKey, company.fiscalYearStartMonth, company.ownerId, company.updatedAt, company.id);
    }

    private Department createSeedDepartment(long companyId, String name, String costCenter, String budget) {
        Department department = new Department();
        department.companyId = companyId;
        department.name = name;
        department.costCenter = costCenter == null ? "" : costCenter;
        department.budget = money(budget);
        department.status = 1;
        String now = InMemoryStore.now();
        department.createdAt = now;
        department.updatedAt = now;
        return departmentRepository.insert(department);
    }

    public Employee employee(
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
        employee.overtimeBase = employee.salary;
        employee.weekdayOvertimeHours = BigDecimal.ZERO;
        employee.restDayOvertimeHours = BigDecimal.ZERO;
        employee.holidayOvertimeHours = BigDecimal.ZERO;
        employee.overtimePay = BigDecimal.ZERO;
        employee.overtimePolicyNote = overtimePolicyNote();
        employee.socialInsuranceBase = employee.salary;
        employee.socialInsurancePersonalRate = DEFAULT_PENSION_PERSONAL_RATE;
        employee.socialInsuranceCompanyRate = percentageOf(employee.socialInsurance, employee.socialInsuranceBase, DEFAULT_PENSION_COMPANY_RATE);
        employee.socialInsurancePersonalAmount = BigDecimal.ZERO;
        employee.socialInsuranceCompanyAmount = employee.socialInsurance;
        employee.housingFundBase = employee.salary;
        employee.housingFundPersonalRate = DEFAULT_HOUSING_FUND_RATE;
        employee.housingFundCompanyRate = percentageOf(employee.housingFund, employee.housingFundBase, DEFAULT_HOUSING_FUND_RATE);
        employee.housingFundPersonalAmount = BigDecimal.ZERO;
        employee.housingFundCompanyAmount = employee.housingFund;
        employee.personalDeduction = BigDecimal.ZERO;
        employee.netPayEstimate = BigDecimal.ZERO;
        employee.socialInsuranceRegion = DEFAULT_SOCIAL_INSURANCE_REGION;
        employee.hukouType = DEFAULT_HUKOU_TYPE;
        employee.medicalTier = DEFAULT_MEDICAL_TIER;
        employee.pensionBase = clamp(employee.salary, SHENZHEN_PENSION_MIN_BASE, SHENZHEN_PENSION_MAX_BASE);
        employee.medicalBase = clamp(employee.salary, SHENZHEN_MEDICAL_MIN_BASE, SHENZHEN_MEDICAL_MAX_BASE);
        employee.unemploymentBase = clamp(employee.salary, SHENZHEN_UNEMPLOYMENT_MIN_BASE, SHENZHEN_UNEMPLOYMENT_MAX_BASE);
        employee.workInjuryBase = max(employee.salary, SHENZHEN_UNEMPLOYMENT_MIN_BASE);
        employee.maternityBase = employee.medicalBase;
        employee.workInjuryCompanyRate = DEFAULT_WORK_INJURY_COMPANY_RATE;
        employee.socialInsurancePolicyNote = shenzhenPolicyNote();
        employee.monthlyCost = money(monthlyCost);
        hydrateEmployeePayroll(employee);
        employee.emergencyContact = emergencyContact;
        stamp(employee);
        employee.id = insert("""
            INSERT INTO employees (
                company_id, user_id, department_id, name, email, phone, position, employment_type, status,
                access_role, access_scope, hire_date, leave_date, salary, social_insurance, housing_fund, tax_estimate,
                overtime_base, weekday_overtime_hours, rest_day_overtime_hours, holiday_overtime_hours, overtime_pay, overtime_policy_note,
                social_insurance_base, social_insurance_personal_rate, social_insurance_company_rate, social_insurance_personal_amount,
                social_insurance_company_amount, housing_fund_base, housing_fund_personal_rate, housing_fund_company_rate,
                housing_fund_personal_amount, housing_fund_company_amount, personal_deduction, net_pay_estimate,
                social_insurance_region, hukou_type, medical_tier, pension_base, medical_base, unemployment_base, work_injury_base,
                maternity_base, work_injury_company_rate, social_insurance_policy_note, monthly_cost, emergency_contact, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindEmployee(ps, employee));
        employees.put(employee.id, employee);
        attachDepartmentName(employee);
        return employee;
    }

    public void saveEmployee(Employee employee) {
        hydrateEmployeePayroll(employee);
        employees.put(employee.id, employee);
        jdbc.update("""
            UPDATE employees SET company_id = ?, user_id = ?, department_id = ?, employee_no = ?, name = ?, legal_name = ?,
                preferred_name = ?, email = ?, phone = ?, position = ?, direct_manager_employee_id = ?, job_level = ?,
                work_location = ?, employment_type = ?, status = ?, access_role = ?, access_scope = ?, hire_date = ?, leave_date = ?,
                probation_start_date = ?, probation_end_date = ?, contract_start_date = ?, contract_end_date = ?, contract_type = ?,
                contract_status = ?, education_level = ?, graduation_school = ?, major = ?, graduation_date = ?, graduation_year = ?,
                graduate_status = ?, skill_tags = ?, resume_summary = ?, material_status = ?, profile_verified_at = ?, profile_verified_by = ?,
                salary = ?, overtime_base = ?, weekday_overtime_hours = ?, rest_day_overtime_hours = ?, holiday_overtime_hours = ?,
                overtime_pay = ?, overtime_policy_note = ?,
                social_insurance = ?, housing_fund = ?, tax_estimate = ?, social_insurance_base = ?, social_insurance_personal_rate = ?,
                social_insurance_company_rate = ?, social_insurance_personal_amount = ?, social_insurance_company_amount = ?,
                housing_fund_base = ?, housing_fund_personal_rate = ?, housing_fund_company_rate = ?, housing_fund_personal_amount = ?,
                housing_fund_company_amount = ?, personal_deduction = ?, net_pay_estimate = ?, social_insurance_region = ?,
                hukou_type = ?, medical_tier = ?, pension_base = ?, medical_base = ?, unemployment_base = ?, work_injury_base = ?,
                maternity_base = ?, work_injury_company_rate = ?, social_insurance_policy_note = ?, monthly_cost = ?, emergency_contact = ?,
                updated_at = ?
            WHERE id = ?
            """, employee.companyId, employee.userId, employee.departmentId, employee.employeeNo, employee.name, employee.legalName,
            employee.preferredName, employee.email, employee.phone, employee.position, employee.directManagerEmployeeId, employee.jobLevel,
            employee.workLocation, employee.employmentType, employee.status, employee.accessRole, employee.accessScope, employee.hireDate, employee.leaveDate,
            employee.probationStartDate, employee.probationEndDate, employee.contractStartDate, employee.contractEndDate, employee.contractType,
            employee.contractStatus, employee.educationLevel, employee.graduationSchool, employee.major, employee.graduationDate, employee.graduationYear,
            employee.graduateStatus, employee.skillTags, employee.resumeSummary, employee.materialStatus, employee.profileVerifiedAt, employee.profileVerifiedBy,
            moneyText(employee.salary), moneyText(employee.overtimeBase), moneyText(employee.weekdayOvertimeHours),
            moneyText(employee.restDayOvertimeHours), moneyText(employee.holidayOvertimeHours), moneyText(employee.overtimePay),
            employee.overtimePolicyNote, moneyText(employee.socialInsurance), moneyText(employee.housingFund),
            moneyText(employee.taxEstimate), moneyText(employee.socialInsuranceBase), moneyText(employee.socialInsurancePersonalRate),
            moneyText(employee.socialInsuranceCompanyRate), moneyText(employee.socialInsurancePersonalAmount),
            moneyText(employee.socialInsuranceCompanyAmount), moneyText(employee.housingFundBase),
            moneyText(employee.housingFundPersonalRate), moneyText(employee.housingFundCompanyRate),
            moneyText(employee.housingFundPersonalAmount), moneyText(employee.housingFundCompanyAmount),
            moneyText(employee.personalDeduction), moneyText(employee.netPayEstimate), employee.socialInsuranceRegion,
            employee.hukouType, employee.medicalTier, moneyText(employee.pensionBase), moneyText(employee.medicalBase),
            moneyText(employee.unemploymentBase), moneyText(employee.workInjuryBase), moneyText(employee.maternityBase),
            moneyText(employee.workInjuryCompanyRate), employee.socialInsurancePolicyNote, moneyText(employee.monthlyCost), employee.emergencyContact,
            employee.updatedAt, employee.id);
        attachDepartmentName(employee);
    }

    private void deleteEmployee(long id) {
        jdbc.update("DELETE FROM employee_certificates WHERE employee_id = ?", id);
        jdbc.update("DELETE FROM employee_experiences WHERE employee_id = ?", id);
        jdbc.update("DELETE FROM employment_events WHERE employee_id = ?", id);
        jdbc.update("DELETE FROM payroll_run_items WHERE employee_id = ?", id);
        jdbc.update("DELETE FROM employees WHERE id = ?", id);
        employees.remove(id);
        employmentEvents.entrySet().removeIf(entry -> entry.getValue().employeeId == id);
    }

    public void replaceEmployeeCertificates(long employeeId, List<EmployeeCertificate> certificates) {
        jdbc.update("DELETE FROM employee_certificates WHERE employee_id = ?", employeeId);
        List<EmployeeCertificate> saved = new ArrayList<>();
        for (EmployeeCertificate certificate : certificates) {
            if (certificate == null || isBlank(certificate.name)) {
                continue;
            }
            certificate.employeeId = employeeId;
            certificate.verificationStatus = blankToDefault(certificate.verificationStatus, "unverified");
            certificate.materialStatus = blankToDefault(certificate.materialStatus, "missing");
            stamp(certificate);
            certificate.id = insert("""
                INSERT INTO employee_certificates (
                    employee_id, name, category, level, issuer, certificate_no, issue_date, expiry_date,
                    verification_status, material_status, note, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ps -> bindEmployeeCertificate(ps, certificate));
            saved.add(certificate);
        }
        Employee employee = employees.get(employeeId);
        if (employee != null) {
            employee.certificates = saved;
        }
    }

    public void replaceEmployeeExperiences(long employeeId, List<EmployeeExperience> experiences) {
        jdbc.update("DELETE FROM employee_experiences WHERE employee_id = ?", employeeId);
        List<EmployeeExperience> saved = new ArrayList<>();
        for (EmployeeExperience experience : experiences) {
            if (experience == null || (isBlank(experience.organization) && isBlank(experience.title) && isBlank(experience.description))) {
                continue;
            }
            experience.employeeId = employeeId;
            experience.type = blankToDefault(experience.type, "work");
            experience.organization = blankToDefault(experience.organization, "未填写");
            stamp(experience);
            experience.id = insert("""
                INSERT INTO employee_experiences (
                    employee_id, type, organization, title, start_date, end_date, description, achievements,
                    skills, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ps -> bindEmployeeExperience(ps, experience));
            saved.add(experience);
        }
        Employee employee = employees.get(employeeId);
        if (employee != null) {
            employee.experiences = saved;
        }
    }

    public EmploymentEvent event(long companyId, long employeeId, String type, String effectiveDate, String note, long operatorUserId) {
        EmploymentEvent event = new EmploymentEvent();
        event.companyId = companyId;
        event.employeeId = employeeId;
        event.type = type;
        event.effectiveDate = effectiveDate;
        event.note = note == null ? "" : note;
        event.operatorUserId = operatorUserId;
        event.createdAt = InMemoryStore.now();
        event.id = insert("""
            INSERT INTO employment_events (company_id, employee_id, type, effective_date, note, operator_user_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, ps -> {
            ps.setLong(1, event.companyId);
            ps.setLong(2, event.employeeId);
            ps.setString(3, event.type);
            ps.setString(4, event.effectiveDate);
            ps.setString(5, event.note);
            ps.setLong(6, event.operatorUserId);
            ps.setString(7, event.createdAt);
        });
        employmentEvents.put(event.id, event);
        return event;
    }

    public EntityTransfer entityTransfer(
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
        stamp(transfer);
        transfer.id = insert("""
            INSERT INTO entity_transfers (
                from_entity_id, to_entity_id, transfer_type, amount, currency, transfer_date, note, status,
                operator_user_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindEntityTransfer(ps, transfer));
        attachEntityTransferNames(transfer);
        entityTransfers.put(transfer.id, transfer);
        return transfer;
    }

    public AuditLog auditLog(
        long companyId,
        String entityType,
        long entityId,
        String action,
        String summary,
        long actorUserId,
        String actorName
    ) {
        return auditLogRepository.append(new AuditEvent(
            companyId,
            entityType,
            entityId,
            action,
            summary,
            actorUserId,
            actorName,
            InMemoryStore.now()
        ));
    }

    private void attachDepartmentNames() {
        employees.values().forEach(this::attachDepartmentName);
    }

    private void attachDepartmentName(Employee employee) {
        employee.departmentName = Optional.ofNullable(employee.departmentId)
            .flatMap(departmentRepository::findById)
            .map(department -> department.name)
            .orElse(null);
    }

    private void attachEmployeeProfileDetails(long companyId, List<Employee> companyEmployees) {
        if (companyEmployees.isEmpty()) {
            return;
        }
        Map<Long, List<EmployeeCertificate>> certificatesByEmployee = new HashMap<>();
        jdbc.query("""
            SELECT certificate.*
            FROM employee_certificates certificate
            JOIN employees employee ON employee.id = certificate.employee_id
            WHERE employee.company_id = ?
            ORDER BY certificate.employee_id, COALESCE(certificate.expiry_date, '9999-12-31'), certificate.id
            """, (rs, rowNum) -> mapEmployeeCertificate(rs), companyId).forEach(certificate ->
            certificatesByEmployee.computeIfAbsent(certificate.employeeId, ignored -> new ArrayList<>()).add(certificate)
        );
        Map<Long, List<EmployeeExperience>> experiencesByEmployee = new HashMap<>();
        jdbc.query("""
            SELECT experience.*
            FROM employee_experiences experience
            JOIN employees employee ON employee.id = experience.employee_id
            WHERE employee.company_id = ?
            ORDER BY experience.employee_id, COALESCE(experience.start_date, '0000-01-01') DESC, experience.id DESC
            """, (rs, rowNum) -> mapEmployeeExperience(rs), companyId).forEach(experience ->
            experiencesByEmployee.computeIfAbsent(experience.employeeId, ignored -> new ArrayList<>()).add(experience)
        );
        for (Employee employee : companyEmployees) {
            employee.certificates = List.copyOf(certificatesByEmployee.getOrDefault(employee.id, List.of()));
            employee.experiences = List.copyOf(experiencesByEmployee.getOrDefault(employee.id, List.of()));
        }
    }

    private void attachEntityTransferNames(EntityTransfer transfer) {
        transfer.fromEntityName = findCompany(transfer.fromEntityId).map(company -> company.name).orElse(null);
        transfer.toEntityName = findCompany(transfer.toEntityId).map(company -> company.name).orElse(null);
    }

    private Company mapCompany(ResultSet rs) throws SQLException {
        Company company = new Company();
        company.id = rs.getLong("id");
        company.name = rs.getString("name");
        company.entityType = rs.getString("entity_type");
        company.creditCode = rs.getString("credit_code");
        company.industry = rs.getString("industry");
        company.taxpayerType = rs.getString("taxpayer_type");
        company.currency = rs.getString("currency");
        company.country = rs.getString("country");
        company.province = rs.getString("province");
        company.city = rs.getString("city");
        company.district = rs.getString("district");
        company.registeredAddress = rs.getString("registered_address");
        company.operatingRegion = rs.getString("operating_region");
        company.taxAuthority = rs.getString("tax_authority");
        company.policyProfileKey = rs.getString("policy_profile_key");
        company.fiscalYearStartMonth = rs.getInt("fiscal_year_start_month");
        company.ownerId = rs.getLong("owner_id");
        company.createdAt = rs.getString("created_at");
        company.updatedAt = rs.getString("updated_at");
        return company;
    }

    private String regionLabel(Company company) {
        return List.of(company.country, company.province, company.city, company.district).stream()
            .filter(value -> value != null && !value.isBlank())
            .reduce((left, right) -> left + "/" + right)
            .orElse("中国");
    }

    private String defaultPolicyProfileKey(Company company) {
        return company != null && company.city != null && company.city.contains("深圳")
            ? SHENZHEN_STARTUP_POLICY_PROFILE
            : DEFAULT_POLICY_PROFILE;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private boolean isBootstrapMode() {
        return "bootstrap".equals(bootstrapMode);
    }

    private Employee mapEmployee(ResultSet rs) throws SQLException {
        Employee employee = new Employee();
        employee.id = rs.getLong("id");
        employee.companyId = rs.getLong("company_id");
        employee.userId = nullableLong(rs, "user_id");
        employee.departmentId = nullableLong(rs, "department_id");
        employee.employeeNo = rs.getString("employee_no");
        employee.name = rs.getString("name");
        employee.legalName = rs.getString("legal_name");
        employee.preferredName = rs.getString("preferred_name");
        employee.email = rs.getString("email");
        employee.phone = rs.getString("phone");
        employee.position = rs.getString("position");
        employee.directManagerEmployeeId = nullableLong(rs, "direct_manager_employee_id");
        employee.jobLevel = rs.getString("job_level");
        employee.workLocation = rs.getString("work_location");
        employee.employmentType = rs.getString("employment_type");
        employee.status = rs.getString("status");
        employee.accessRole = rs.getString("access_role");
        employee.accessScope = rs.getString("access_scope");
        employee.hireDate = rs.getString("hire_date");
        employee.leaveDate = rs.getString("leave_date");
        employee.probationStartDate = rs.getString("probation_start_date");
        employee.probationEndDate = rs.getString("probation_end_date");
        employee.contractStartDate = rs.getString("contract_start_date");
        employee.contractEndDate = rs.getString("contract_end_date");
        employee.contractType = rs.getString("contract_type");
        employee.contractStatus = rs.getString("contract_status");
        employee.educationLevel = rs.getString("education_level");
        employee.graduationSchool = rs.getString("graduation_school");
        employee.major = rs.getString("major");
        employee.graduationDate = rs.getString("graduation_date");
        int graduationYear = rs.getInt("graduation_year");
        employee.graduationYear = rs.wasNull() ? null : graduationYear;
        employee.graduateStatus = rs.getString("graduate_status");
        employee.skillTags = rs.getString("skill_tags");
        employee.resumeSummary = rs.getString("resume_summary");
        employee.materialStatus = rs.getString("material_status");
        employee.profileVerifiedAt = rs.getString("profile_verified_at");
        employee.profileVerifiedBy = nullableLong(rs, "profile_verified_by");
        employee.salary = money(rs.getString("salary"));
        employee.overtimeBase = money(rs.getString("overtime_base"));
        employee.weekdayOvertimeHours = money(rs.getString("weekday_overtime_hours"));
        employee.restDayOvertimeHours = money(rs.getString("rest_day_overtime_hours"));
        employee.holidayOvertimeHours = money(rs.getString("holiday_overtime_hours"));
        employee.overtimePay = money(rs.getString("overtime_pay"));
        employee.overtimePolicyNote = rs.getString("overtime_policy_note");
        employee.socialInsurance = money(rs.getString("social_insurance"));
        employee.housingFund = money(rs.getString("housing_fund"));
        employee.taxEstimate = money(rs.getString("tax_estimate"));
        employee.socialInsuranceBase = money(rs.getString("social_insurance_base"));
        employee.socialInsurancePersonalRate = money(rs.getString("social_insurance_personal_rate"));
        employee.socialInsuranceCompanyRate = money(rs.getString("social_insurance_company_rate"));
        employee.socialInsurancePersonalAmount = money(rs.getString("social_insurance_personal_amount"));
        employee.socialInsuranceCompanyAmount = money(rs.getString("social_insurance_company_amount"));
        employee.housingFundBase = money(rs.getString("housing_fund_base"));
        employee.housingFundPersonalRate = money(rs.getString("housing_fund_personal_rate"));
        employee.housingFundCompanyRate = money(rs.getString("housing_fund_company_rate"));
        employee.housingFundPersonalAmount = money(rs.getString("housing_fund_personal_amount"));
        employee.housingFundCompanyAmount = money(rs.getString("housing_fund_company_amount"));
        employee.personalDeduction = money(rs.getString("personal_deduction"));
        employee.netPayEstimate = money(rs.getString("net_pay_estimate"));
        employee.socialInsuranceRegion = rs.getString("social_insurance_region");
        employee.hukouType = rs.getString("hukou_type");
        employee.medicalTier = rs.getString("medical_tier");
        employee.pensionBase = money(rs.getString("pension_base"));
        employee.medicalBase = money(rs.getString("medical_base"));
        employee.unemploymentBase = money(rs.getString("unemployment_base"));
        employee.workInjuryBase = money(rs.getString("work_injury_base"));
        employee.maternityBase = money(rs.getString("maternity_base"));
        employee.workInjuryCompanyRate = money(rs.getString("work_injury_company_rate"));
        employee.socialInsurancePolicyNote = rs.getString("social_insurance_policy_note");
        employee.monthlyCost = money(rs.getString("monthly_cost"));
        employee.emergencyContact = rs.getString("emergency_contact");
        employee.createdAt = rs.getString("created_at");
        employee.updatedAt = rs.getString("updated_at");
        hydrateEmployeePayroll(employee);
        return employee;
    }

    private EmployeeCertificate mapEmployeeCertificate(ResultSet rs) throws SQLException {
        EmployeeCertificate certificate = new EmployeeCertificate();
        certificate.id = rs.getLong("id");
        certificate.employeeId = rs.getLong("employee_id");
        certificate.name = rs.getString("name");
        certificate.category = rs.getString("category");
        certificate.level = rs.getString("level");
        certificate.issuer = rs.getString("issuer");
        certificate.certificateNo = rs.getString("certificate_no");
        certificate.issueDate = rs.getString("issue_date");
        certificate.expiryDate = rs.getString("expiry_date");
        certificate.verificationStatus = rs.getString("verification_status");
        certificate.materialStatus = rs.getString("material_status");
        certificate.note = rs.getString("note");
        certificate.createdAt = rs.getString("created_at");
        certificate.updatedAt = rs.getString("updated_at");
        return certificate;
    }

    private EmployeeExperience mapEmployeeExperience(ResultSet rs) throws SQLException {
        EmployeeExperience experience = new EmployeeExperience();
        experience.id = rs.getLong("id");
        experience.employeeId = rs.getLong("employee_id");
        experience.type = rs.getString("type");
        experience.organization = rs.getString("organization");
        experience.title = rs.getString("title");
        experience.startDate = rs.getString("start_date");
        experience.endDate = rs.getString("end_date");
        experience.description = rs.getString("description");
        experience.achievements = rs.getString("achievements");
        experience.skills = rs.getString("skills");
        experience.createdAt = rs.getString("created_at");
        experience.updatedAt = rs.getString("updated_at");
        return experience;
    }

    private EmploymentEvent mapEmploymentEvent(ResultSet rs) throws SQLException {
        EmploymentEvent event = new EmploymentEvent();
        event.id = rs.getLong("id");
        event.companyId = rs.getLong("company_id");
        event.employeeId = rs.getLong("employee_id");
        event.type = rs.getString("type");
        event.effectiveDate = rs.getString("effective_date");
        event.note = rs.getString("note");
        event.operatorUserId = rs.getLong("operator_user_id");
        event.createdAt = rs.getString("created_at");
        return event;
    }

    private EntityTransfer mapEntityTransfer(ResultSet rs) throws SQLException {
        EntityTransfer transfer = new EntityTransfer();
        transfer.id = rs.getLong("id");
        transfer.fromEntityId = rs.getLong("from_entity_id");
        transfer.toEntityId = rs.getLong("to_entity_id");
        transfer.transferType = rs.getString("transfer_type");
        transfer.amount = money(rs.getString("amount"));
        transfer.currency = rs.getString("currency");
        transfer.transferDate = rs.getString("transfer_date");
        transfer.note = rs.getString("note");
        transfer.status = rs.getString("status");
        transfer.operatorUserId = rs.getLong("operator_user_id");
        transfer.createdAt = rs.getString("created_at");
        transfer.updatedAt = rs.getString("updated_at");
        attachEntityTransferNames(transfer);
        return transfer;
    }

    private void bindEmployee(PreparedStatement ps, Employee employee) throws SQLException {
        ps.setLong(1, employee.companyId);
        setLongOrNull(ps, 2, employee.userId);
        setLongOrNull(ps, 3, employee.departmentId);
        ps.setString(4, employee.name);
        ps.setString(5, employee.email);
        ps.setString(6, employee.phone);
        ps.setString(7, employee.position);
        ps.setString(8, employee.employmentType);
        ps.setString(9, employee.status);
        ps.setString(10, employee.accessRole);
        ps.setString(11, employee.accessScope);
        ps.setString(12, employee.hireDate);
        ps.setString(13, employee.leaveDate);
        ps.setString(14, moneyText(employee.salary));
        ps.setString(15, moneyText(employee.overtimeBase));
        ps.setString(16, moneyText(employee.weekdayOvertimeHours));
        ps.setString(17, moneyText(employee.restDayOvertimeHours));
        ps.setString(18, moneyText(employee.holidayOvertimeHours));
        ps.setString(19, moneyText(employee.overtimePay));
        ps.setString(20, employee.overtimePolicyNote);
        ps.setString(21, moneyText(employee.socialInsurance));
        ps.setString(22, moneyText(employee.housingFund));
        ps.setString(23, moneyText(employee.taxEstimate));
        ps.setString(24, moneyText(employee.socialInsuranceBase));
        ps.setString(25, moneyText(employee.socialInsurancePersonalRate));
        ps.setString(26, moneyText(employee.socialInsuranceCompanyRate));
        ps.setString(27, moneyText(employee.socialInsurancePersonalAmount));
        ps.setString(28, moneyText(employee.socialInsuranceCompanyAmount));
        ps.setString(29, moneyText(employee.housingFundBase));
        ps.setString(30, moneyText(employee.housingFundPersonalRate));
        ps.setString(31, moneyText(employee.housingFundCompanyRate));
        ps.setString(32, moneyText(employee.housingFundPersonalAmount));
        ps.setString(33, moneyText(employee.housingFundCompanyAmount));
        ps.setString(34, moneyText(employee.personalDeduction));
        ps.setString(35, moneyText(employee.netPayEstimate));
        ps.setString(36, employee.socialInsuranceRegion);
        ps.setString(37, employee.hukouType);
        ps.setString(38, employee.medicalTier);
        ps.setString(39, moneyText(employee.pensionBase));
        ps.setString(40, moneyText(employee.medicalBase));
        ps.setString(41, moneyText(employee.unemploymentBase));
        ps.setString(42, moneyText(employee.workInjuryBase));
        ps.setString(43, moneyText(employee.maternityBase));
        ps.setString(44, moneyText(employee.workInjuryCompanyRate));
        ps.setString(45, employee.socialInsurancePolicyNote);
        ps.setString(46, moneyText(employee.monthlyCost));
        ps.setString(47, employee.emergencyContact);
        ps.setString(48, employee.createdAt);
        ps.setString(49, employee.updatedAt);
    }

    private void bindEmployeeCertificate(PreparedStatement ps, EmployeeCertificate certificate) throws SQLException {
        ps.setLong(1, certificate.employeeId);
        ps.setString(2, certificate.name);
        ps.setString(3, certificate.category);
        ps.setString(4, certificate.level);
        ps.setString(5, certificate.issuer);
        ps.setString(6, certificate.certificateNo);
        ps.setString(7, certificate.issueDate);
        ps.setString(8, certificate.expiryDate);
        ps.setString(9, certificate.verificationStatus);
        ps.setString(10, certificate.materialStatus);
        ps.setString(11, certificate.note);
        ps.setString(12, certificate.createdAt);
        ps.setString(13, certificate.updatedAt);
    }

    private void bindEmployeeExperience(PreparedStatement ps, EmployeeExperience experience) throws SQLException {
        ps.setLong(1, experience.employeeId);
        ps.setString(2, experience.type);
        ps.setString(3, experience.organization);
        ps.setString(4, experience.title);
        ps.setString(5, experience.startDate);
        ps.setString(6, experience.endDate);
        ps.setString(7, experience.description);
        ps.setString(8, experience.achievements);
        ps.setString(9, experience.skills);
        ps.setString(10, experience.createdAt);
        ps.setString(11, experience.updatedAt);
    }

    private void bindEntityTransfer(PreparedStatement ps, EntityTransfer transfer) throws SQLException {
        ps.setLong(1, transfer.fromEntityId);
        ps.setLong(2, transfer.toEntityId);
        ps.setString(3, transfer.transferType);
        ps.setString(4, moneyText(transfer.amount));
        ps.setString(5, transfer.currency);
        ps.setString(6, transfer.transferDate);
        ps.setString(7, transfer.note);
        ps.setString(8, transfer.status);
        ps.setLong(9, transfer.operatorUserId);
        ps.setString(10, transfer.createdAt);
        ps.setString(11, transfer.updatedAt);
    }

    private long insert(String sql, SqlBinder binder) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[] { "id" });
            binder.bind(ps);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Database did not return a generated key");
        }
        return key.longValue();
    }

    private void forEachRow(String sql, SqlRowConsumer consumer) {
        jdbc.query(sql, (org.springframework.jdbc.core.RowCallbackHandler) consumer::accept);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static void setLongOrNull(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setLong(index, value);
        }
    }

    private static boolean hydrateEmployeePayroll(Employee employee) {
        boolean updated = false;
        BigDecimal salary = money(employee.salary);
        if (!sameMoney(employee.salary, salary)) {
            employee.salary = salary;
            updated = true;
        }
        String region = blankToDefault(employee.socialInsuranceRegion, DEFAULT_SOCIAL_INSURANCE_REGION);
        String hukouType = normalizeHukouType(employee.hukouType);
        String medicalTier = normalizeMedicalTier(employee.medicalTier);
        String policyNote = shenzhenPolicyNote();
        if (!sameText(employee.socialInsuranceRegion, region)) {
            employee.socialInsuranceRegion = region;
            updated = true;
        }
        if (!sameText(employee.hukouType, hukouType)) {
            employee.hukouType = hukouType;
            updated = true;
        }
        if (!sameText(employee.medicalTier, medicalTier)) {
            employee.medicalTier = medicalTier;
            updated = true;
        }
        if (!sameText(employee.socialInsurancePolicyNote, policyNote)) {
            employee.socialInsurancePolicyNote = policyNote;
            updated = true;
        }
        String overtimePolicyNote = overtimePolicyNote();
        if (!sameText(employee.overtimePolicyNote, overtimePolicyNote)) {
            employee.overtimePolicyNote = overtimePolicyNote;
            updated = true;
        }

        List<String> warnings = new ArrayList<>();
        BigDecimal overtimeBase = boundedBase("加班工资", firstPositive(employee.overtimeBase, salary),
            SHENZHEN_UNEMPLOYMENT_MIN_BASE, null, warnings);
        BigDecimal weekdayOvertimeHours = nonNegative(employee.weekdayOvertimeHours);
        BigDecimal restDayOvertimeHours = nonNegative(employee.restDayOvertimeHours);
        BigDecimal holidayOvertimeHours = nonNegative(employee.holidayOvertimeHours);
        BigDecimal overtimePay = overtimePay(overtimeBase, weekdayOvertimeHours, restDayOvertimeHours, holidayOvertimeHours);
        BigDecimal pensionBase = boundedBase("养老保险", firstPositive(employee.pensionBase, firstPositive(employee.socialInsuranceBase, salary)),
            SHENZHEN_PENSION_MIN_BASE, SHENZHEN_PENSION_MAX_BASE, warnings);
        BigDecimal medicalBase = boundedBase("医疗保险", firstPositive(employee.medicalBase, firstPositive(employee.socialInsuranceBase, salary)),
            SHENZHEN_MEDICAL_MIN_BASE, SHENZHEN_MEDICAL_MAX_BASE, warnings);
        BigDecimal unemploymentBase = boundedBase("失业保险", firstPositive(employee.unemploymentBase, salary),
            SHENZHEN_UNEMPLOYMENT_MIN_BASE, SHENZHEN_UNEMPLOYMENT_MAX_BASE, warnings);
        BigDecimal maternityBase = boundedBase("生育保险", firstPositive(employee.maternityBase, medicalBase),
            SHENZHEN_MEDICAL_MIN_BASE, SHENZHEN_MEDICAL_MAX_BASE, warnings);
        BigDecimal workInjuryBase = max(firstPositive(employee.workInjuryBase, salary), SHENZHEN_UNEMPLOYMENT_MIN_BASE);
        BigDecimal housingFundBase = boundedBase("住房公积金", firstPositive(employee.housingFundBase, salary),
            SHENZHEN_HOUSING_FUND_MIN_BASE, SHENZHEN_HOUSING_FUND_MAX_BASE, warnings);
        BigDecimal workInjuryCompanyRate = boundedRate("工伤公司费率",
            firstPositive(employee.workInjuryCompanyRate, DEFAULT_WORK_INJURY_COMPANY_RATE),
            DEFAULT_WORK_INJURY_COMPANY_RATE, MAX_WORK_INJURY_COMPANY_RATE, warnings);
        BigDecimal housingFundPersonalRate = boundedRate("公积金个人比例",
            firstPositive(employee.housingFundPersonalRate, DEFAULT_HOUSING_FUND_RATE),
            MIN_HOUSING_FUND_RATE, MAX_HOUSING_FUND_RATE, warnings);
        BigDecimal housingFundCompanyRate = boundedRate("公积金公司比例",
            firstPositive(employee.housingFundCompanyRate, percentageOf(firstPositive(employee.housingFundCompanyAmount, employee.housingFund),
                housingFundBase, DEFAULT_HOUSING_FUND_RATE)),
            MIN_HOUSING_FUND_RATE, MAX_HOUSING_FUND_RATE, warnings);

        if (!sameMoney(employee.pensionBase, pensionBase)) {
            employee.pensionBase = pensionBase;
            updated = true;
        }
        if (!sameMoney(employee.medicalBase, medicalBase)) {
            employee.medicalBase = medicalBase;
            updated = true;
        }
        if (!sameMoney(employee.unemploymentBase, unemploymentBase)) {
            employee.unemploymentBase = unemploymentBase;
            updated = true;
        }
        if (!sameMoney(employee.maternityBase, maternityBase)) {
            employee.maternityBase = maternityBase;
            updated = true;
        }
        if (!sameMoney(employee.workInjuryBase, workInjuryBase)) {
            employee.workInjuryBase = workInjuryBase;
            updated = true;
        }
        if (!sameMoney(employee.workInjuryCompanyRate, workInjuryCompanyRate)) {
            employee.workInjuryCompanyRate = workInjuryCompanyRate;
            updated = true;
        }
        if (!sameMoney(employee.housingFundBase, housingFundBase)) {
            employee.housingFundBase = housingFundBase;
            updated = true;
        }
        if (!sameMoney(employee.housingFundPersonalRate, housingFundPersonalRate)) {
            employee.housingFundPersonalRate = housingFundPersonalRate;
            updated = true;
        }
        if (!sameMoney(employee.housingFundCompanyRate, housingFundCompanyRate)) {
            employee.housingFundCompanyRate = housingFundCompanyRate;
            updated = true;
        }
        if (!sameMoney(employee.overtimeBase, overtimeBase)) {
            employee.overtimeBase = overtimeBase;
            updated = true;
        }
        if (!sameMoney(employee.weekdayOvertimeHours, weekdayOvertimeHours)) {
            employee.weekdayOvertimeHours = weekdayOvertimeHours;
            updated = true;
        }
        if (!sameMoney(employee.restDayOvertimeHours, restDayOvertimeHours)) {
            employee.restDayOvertimeHours = restDayOvertimeHours;
            updated = true;
        }
        if (!sameMoney(employee.holidayOvertimeHours, holidayOvertimeHours)) {
            employee.holidayOvertimeHours = holidayOvertimeHours;
            updated = true;
        }
        if (!sameMoney(employee.overtimePay, overtimePay)) {
            employee.overtimePay = overtimePay;
            updated = true;
        }
        if (employee.taxEstimate == null) {
            employee.taxEstimate = BigDecimal.ZERO;
            updated = true;
        }
        if (employee.personalDeduction == null) {
            employee.personalDeduction = BigDecimal.ZERO;
            updated = true;
        }

        BigDecimal medicalPersonalRate = "tier2".equals(medicalTier) ? DEFAULT_MEDICAL_TIER2_PERSONAL_RATE : DEFAULT_MEDICAL_TIER1_PERSONAL_RATE;
        BigDecimal medicalCompanyRate = "tier2".equals(medicalTier) ? DEFAULT_MEDICAL_TIER2_COMPANY_RATE : DEFAULT_MEDICAL_TIER1_COMPANY_RATE;
        List<SocialInsuranceItem> socialInsuranceItems = new ArrayList<>();
        socialInsuranceItems.add(socialInsuranceItem(
            "pension", "养老保险", "养老", pensionBase, SHENZHEN_PENSION_MIN_BASE, SHENZHEN_PENSION_MAX_BASE,
            DEFAULT_PENSION_PERSONAL_RATE, DEFAULT_PENSION_COMPANY_RATE,
            "广东企业职工养老基数 2025-07 起；单位 16%，个人 8%", "2025-07-01 至 2026-06-30"
        ));
        if (isLocalHukou(hukouType)) {
            socialInsuranceItems.add(socialInsuranceItem(
                "localSupplementPension", "地方补充养老", "养老", pensionBase, SHENZHEN_PENSION_MIN_BASE, SHENZHEN_PENSION_MAX_BASE,
                BigDecimal.ZERO, DEFAULT_LOCAL_SUPPLEMENT_PENSION_COMPANY_RATE,
                "深圳本市户籍地方补充养老，单位承担", "长期政策，按最新通知调整"
            ));
        }
        socialInsuranceItems.add(socialInsuranceItem(
            "medical", "医疗保险" + ("tier2".equals(medicalTier) ? "二档" : "一档"), "医疗", medicalBase,
            SHENZHEN_MEDICAL_MIN_BASE, SHENZHEN_MEDICAL_MAX_BASE, medicalPersonalRate, medicalCompanyRate,
            "深圳医保 2026 基数；一档单位 6%/个人 2%，二档单位 1.5%/个人 0.5%", "2026-01-01 至 2026-12-31"
        ));
        socialInsuranceItems.add(socialInsuranceItem(
            "maternity", "生育保险", "生育", maternityBase, SHENZHEN_MEDICAL_MIN_BASE, SHENZHEN_MEDICAL_MAX_BASE,
            BigDecimal.ZERO, DEFAULT_MATERNITY_COMPANY_RATE,
            "深圳生育保险按职工医保基数，单位 0.5%，个人不缴", "2026-01-01 至 2026-12-31"
        ));
        socialInsuranceItems.add(socialInsuranceItem(
            "unemployment", "失业保险", "失业", unemploymentBase, SHENZHEN_UNEMPLOYMENT_MIN_BASE, SHENZHEN_UNEMPLOYMENT_MAX_BASE,
            DEFAULT_UNEMPLOYMENT_PERSONAL_RATE, DEFAULT_UNEMPLOYMENT_COMPANY_RATE,
            "深圳失业保险 2025-07 至 2026-06 基数；单位 0.8%，个人 0.2%", "2025-07-01 至 2026-06-30"
        ));
        socialInsuranceItems.add(socialInsuranceItem(
            "workInjury", "工伤保险", "工伤", workInjuryBase, SHENZHEN_UNEMPLOYMENT_MIN_BASE, null,
            BigDecimal.ZERO, workInjuryCompanyRate,
            "深圳工伤基数不低于 2520，普通单位按职工工资总额计缴；行业基准费率 0.2%-1.4%，个人不缴", "2024-07-01 起"
        ));

        BigDecimal socialPersonalAmount = socialPersonalAmount(socialInsuranceItems);
        BigDecimal socialCompanyAmount = socialCompanyAmount(socialInsuranceItems);
        BigDecimal housingPersonalAmount = contribution(employee.housingFundBase, employee.housingFundPersonalRate);
        BigDecimal housingCompanyAmount = contribution(employee.housingFundBase, employee.housingFundCompanyRate);
        BigDecimal payableSalary = salary.add(overtimePay);
        BigDecimal monthlyCost = payableSalary.add(socialCompanyAmount).add(housingCompanyAmount);
        BigDecimal netPayEstimate = payableSalary
            .subtract(socialPersonalAmount)
            .subtract(housingPersonalAmount)
            .subtract(money(employee.taxEstimate))
            .subtract(money(employee.personalDeduction));
        if (netPayEstimate.signum() < 0) {
            netPayEstimate = BigDecimal.ZERO;
        }

        if (!sameMoney(employee.socialInsuranceBase, pensionBase)) {
            employee.socialInsuranceBase = pensionBase;
            updated = true;
        }
        BigDecimal aggregatePersonalRate = percentageOf(socialPersonalAmount, pensionBase, BigDecimal.ZERO);
        BigDecimal aggregateCompanyRate = percentageOf(socialCompanyAmount, pensionBase, BigDecimal.ZERO);
        if (!sameMoney(employee.socialInsurancePersonalRate, aggregatePersonalRate)) {
            employee.socialInsurancePersonalRate = aggregatePersonalRate;
            updated = true;
        }
        if (!sameMoney(employee.socialInsuranceCompanyRate, aggregateCompanyRate)) {
            employee.socialInsuranceCompanyRate = aggregateCompanyRate;
            updated = true;
        }
        if (!sameMoney(employee.socialInsurancePersonalAmount, socialPersonalAmount)) {
            employee.socialInsurancePersonalAmount = socialPersonalAmount;
            updated = true;
        }
        if (!sameMoney(employee.socialInsuranceCompanyAmount, socialCompanyAmount)) {
            employee.socialInsuranceCompanyAmount = socialCompanyAmount;
            updated = true;
        }
        if (!sameMoney(employee.housingFundPersonalAmount, housingPersonalAmount)) {
            employee.housingFundPersonalAmount = housingPersonalAmount;
            updated = true;
        }
        if (!sameMoney(employee.housingFundCompanyAmount, housingCompanyAmount)) {
            employee.housingFundCompanyAmount = housingCompanyAmount;
            updated = true;
        }
        if (!sameMoney(employee.socialInsurance, socialCompanyAmount)) {
            employee.socialInsurance = socialCompanyAmount;
            updated = true;
        }
        if (!sameMoney(employee.housingFund, housingCompanyAmount)) {
            employee.housingFund = housingCompanyAmount;
            updated = true;
        }
        if (!sameMoney(employee.monthlyCost, monthlyCost)) {
            employee.monthlyCost = monthlyCost;
            updated = true;
        }
        if (!sameMoney(employee.netPayEstimate, netPayEstimate)) {
            employee.netPayEstimate = netPayEstimate;
            updated = true;
        }
        employee.socialInsuranceItems = socialInsuranceItems;
        employee.socialInsuranceWarnings = warnings;
        return updated;
    }

    private static SocialInsuranceItem socialInsuranceItem(
        String key,
        String name,
        String category,
        BigDecimal base,
        BigDecimal minBase,
        BigDecimal maxBase,
        BigDecimal personalRate,
        BigDecimal companyRate,
        String policyBasis,
        String validPeriod
    ) {
        SocialInsuranceItem item = new SocialInsuranceItem();
        item.key = key;
        item.name = name;
        item.category = category;
        item.base = money(base);
        item.minBase = minBase;
        item.maxBase = maxBase;
        item.personalRate = money(personalRate);
        item.companyRate = money(companyRate);
        item.personalAmount = contribution(item.base, item.personalRate);
        item.companyAmount = contribution(item.base, item.companyRate);
        item.policyBasis = policyBasis;
        item.validPeriod = validPeriod;
        item.status = "normal";
        return item;
    }

    private static BigDecimal socialPersonalAmount(List<SocialInsuranceItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (SocialInsuranceItem item : items) {
            total = total.add(money(item.personalAmount));
        }
        return total;
    }

    private static BigDecimal socialCompanyAmount(List<SocialInsuranceItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (SocialInsuranceItem item : items) {
            total = total.add(money(item.companyAmount));
        }
        return total;
    }

    private static BigDecimal boundedBase(String label, BigDecimal value, BigDecimal min, BigDecimal max, List<String> warnings) {
        BigDecimal safeValue = money(value);
        if (safeValue.compareTo(min) < 0) {
            warnings.add(label + "基数低于深圳当前下限，已按 " + moneyText(min) + " 计算");
            return min;
        }
        if (max != null && safeValue.compareTo(max) > 0) {
            warnings.add(label + "基数高于深圳当前上限，已按 " + moneyText(max) + " 计算");
            return max;
        }
        return safeValue;
    }

    private static BigDecimal boundedRate(String label, BigDecimal value, BigDecimal min, BigDecimal max, List<String> warnings) {
        BigDecimal safeValue = money(value);
        if (safeValue.compareTo(min) < 0) {
            warnings.add(label + "低于当前下限，已按 " + moneyText(min) + "% 计算");
            return min;
        }
        if (safeValue.compareTo(max) > 0) {
            warnings.add(label + "高于当前上限，已按 " + moneyText(max) + "% 计算");
            return max;
        }
        return safeValue;
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        BigDecimal safeValue = money(value);
        if (safeValue.compareTo(min) < 0) {
            return min;
        }
        if (max != null && safeValue.compareTo(max) > 0) {
            return max;
        }
        return safeValue;
    }

    private static BigDecimal max(BigDecimal left, BigDecimal right) {
        return money(left).compareTo(money(right)) >= 0 ? money(left) : money(right);
    }

    private static String normalizeHukouType(String value) {
        String normalized = blankToDefault(value, DEFAULT_HUKOU_TYPE);
        return "local".equals(normalized) || "shenzhen".equals(normalized) || "深户".equals(normalized) ? "local" : "non_local";
    }

    private static boolean isLocalHukou(String value) {
        return "local".equals(normalizeHukouType(value));
    }

    private static String normalizeMedicalTier(String value) {
        String normalized = blankToDefault(value, DEFAULT_MEDICAL_TIER);
        return "tier2".equals(normalized) || "二档".equals(normalized) ? "tier2" : "tier1";
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean sameText(String left, String right) {
        return blankToDefault(left, "").equals(blankToDefault(right, ""));
    }

    private static String shenzhenPolicyNote() {
        return "深圳五险一金演示政策：养老 2025-07 至 2026-06 基数 4775-27549；医保/生育 2026 年基数 6727-33633；失业 2025-07 至 2026-06 基数 2520-44265；工伤基数不低于 2520，普通单位无单人工资上限，行业基准费率 0.2%-1.4%；公积金 2025-07 至 2026-06 基数 2520-44265。";
    }

    private static String overtimePolicyNote() {
        return "国家加班费演示政策：工作日延时 150%，休息日未调休 200%，法定节假日 300%；日工资=月工资收入/21.75，小时工资=月工资收入/(21.75×8)。";
    }

    private static BigDecimal overtimePay(BigDecimal base, BigDecimal weekdayHours, BigDecimal restDayHours, BigDecimal holidayHours) {
        BigDecimal hourlyRate = money(base).divide(OVERTIME_MONTHLY_PAID_DAYS.multiply(STANDARD_DAILY_WORK_HOURS), 6, RoundingMode.HALF_UP);
        BigDecimal weekdayPay = hourlyRate.multiply(nonNegative(weekdayHours)).multiply(WEEKDAY_OVERTIME_RATE);
        BigDecimal restDayPay = hourlyRate.multiply(nonNegative(restDayHours)).multiply(REST_DAY_OVERTIME_RATE);
        BigDecimal holidayPay = hourlyRate.multiply(nonNegative(holidayHours)).multiply(HOLIDAY_OVERTIME_RATE);
        return weekdayPay.add(restDayPay).add(holidayPay).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal contribution(BigDecimal base, BigDecimal rate) {
        return money(base).multiply(money(rate)).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentageOf(BigDecimal amount, BigDecimal base, BigDecimal fallback) {
        BigDecimal safeBase = money(base);
        if (safeBase.signum() <= 0) {
            return fallback;
        }
        BigDecimal safeAmount = money(amount);
        if (safeAmount.signum() <= 0) {
            return fallback;
        }
        return safeAmount.multiply(ONE_HUNDRED).divide(safeBase, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal firstPositive(BigDecimal first, BigDecimal second) {
        BigDecimal safeFirst = money(first);
        return safeFirst.signum() > 0 ? safeFirst : money(second);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        BigDecimal safeValue = money(value);
        return safeValue.signum() < 0 ? BigDecimal.ZERO : safeValue;
    }

    private static boolean isZeroOrLess(BigDecimal value) {
        return money(value).compareTo(BigDecimal.ZERO) <= 0;
    }

    private static boolean sameMoney(BigDecimal left, BigDecimal right) {
        return money(left).compareTo(money(right)) == 0;
    }

    private static BigDecimal money(Object value) {
        return InMemoryStore.money(value);
    }

    private static String moneyText(BigDecimal value) {
        return InMemoryStore.nullToZero(value).stripTrailingZeros().toPlainString();
    }

    private static void stamp(Object model) {
        InMemoryStore.stamp(model);
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlRowConsumer {
        void accept(ResultSet rs) throws SQLException;
    }
}
