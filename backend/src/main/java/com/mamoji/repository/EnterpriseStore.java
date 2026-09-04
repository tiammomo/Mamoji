package com.mamoji.repository;

import com.mamoji.platform.tenant.Company;
import com.mamoji.domain.Models.EntityTransfer;
import com.mamoji.platform.audit.application.AuditLogRepository;
import com.mamoji.platform.audit.domain.AuditEvent;
import com.mamoji.platform.audit.domain.AuditLog;
import com.mamoji.platform.identity.account.application.UserDirectory;
import com.mamoji.people.application.DepartmentRepository;
import com.mamoji.people.application.EmployeeRepository;
import com.mamoji.people.application.EmploymentEventRepository;
import com.mamoji.people.domain.Department;
import com.mamoji.people.domain.Employee;
import com.mamoji.people.domain.EmployeeCompensationPolicy;
import com.mamoji.people.domain.EmploymentEvent;
import com.mamoji.platform.tenant.CompanyProfilePolicy;
import com.mamoji.platform.tenant.CompanyRepository;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Comparator;
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
    public final Map<Long, EntityTransfer> entityTransfers = new ConcurrentHashMap<>();
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
    private final EmployeeRepository employeeRepository;
    private final EmploymentEventRepository employmentEventRepository;
    private final CompanyRepository companyRepository;
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
        EmployeeRepository employeeRepository,
        EmploymentEventRepository employmentEventRepository,
        CompanyRepository companyRepository,
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
        this.employeeRepository = employeeRepository;
        this.employmentEventRepository = employmentEventRepository;
        this.companyRepository = companyRepository;
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
    }

    private void loadAll() {
        entityTransfers.clear();

        forEachRow("SELECT * FROM entity_transfers", rs -> entityTransfers.put(rs.getLong("id"), mapEntityTransfer(rs)));
    }

    /** Reload the process-local compatibility view after a controlled restore. */
    public synchronized void reloadFromDatabase() {
        loadAll();
    }

    private void ensureInitialEnterpriseData() {
        if (companyRepository.existsAny()) {
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
        Company company = newCompany(
            owner.id(),
            bootstrapCompanyName,
            "company",
            bootstrapCompanyCreditCode,
            bootstrapCompanyIndustry,
            bootstrapCompanyTaxpayerType,
            bootstrapCompanyCurrency
        );
        companyRepository.insert(company);

        Department management = createSeedDepartment(company.id, "管理层", "MGMT", "0");
        Employee founder = createSeedEmployee(
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
        createSeedEmploymentEvent(
            company.id,
            founder.id,
            "onboard",
            founder.hireDate,
            "生产环境初始化管理员员工档案",
            owner.id()
        );
        auditLog(company.id, "company", company.id, "bootstrap", "生产环境初始化公司主体: " + company.name, owner.id(), owner.nickname());
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
        companyRepository.insert(company);
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
        companyRepository.insert(household);
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
        companyRepository.findAll().forEach(company -> {
            if (CompanyProfilePolicy.hydrateLegacyDefaults(company)) {
                companyRepository.update(company);
            }
        });
    }

    private void ensureAccessDefaults() {
        Map<Long, UserDirectory.Entry> usersById = userDirectory.findAll().stream()
            .collect(java.util.stream.Collectors.toMap(UserDirectory.Entry::id, user -> user));
        employeeRepository.findAll().forEach(employee -> {
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
                employeeRepository.update(employee);
            }
        });
    }

    private void ensureEmployeePayrollDefaults() {
        employeeRepository.findAll().forEach(employee -> {
            boolean updated = EmployeeCompensationPolicy.hydrate(employee);
            if (updated) {
                employee.updatedAt = InMemoryStore.now();
            }
            employeeRepository.update(employee);
        });
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
        return employeeRepository.insert(employee);
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

    private void attachEntityTransferNames(EntityTransfer transfer) {
        transfer.fromEntityName = companyRepository.findById(transfer.fromEntityId)
            .map(company -> company.name)
            .orElse(null);
        transfer.toEntityName = companyRepository.findById(transfer.toEntityId)
            .map(company -> company.name)
            .orElse(null);
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
