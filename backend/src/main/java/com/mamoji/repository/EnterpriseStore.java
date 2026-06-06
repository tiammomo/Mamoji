package com.mamoji.repository;

import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.Department;
import com.mamoji.domain.Models.Employee;
import com.mamoji.domain.Models.EntityTransfer;
import com.mamoji.domain.Models.EmploymentEvent;
import com.mamoji.domain.Models.TaxItem;
import com.mamoji.domain.Models.User;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

@Component
public class EnterpriseStore {
    public final Map<Long, Company> companies = new ConcurrentHashMap<>();
    public final Map<Long, Department> departments = new ConcurrentHashMap<>();
    public final Map<Long, Employee> employees = new ConcurrentHashMap<>();
    public final Map<Long, EntityTransfer> entityTransfers = new ConcurrentHashMap<>();
    public final Map<Long, EmploymentEvent> employmentEvents = new ConcurrentHashMap<>();
    public final Map<Long, TaxItem> taxItems = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;
    private final InMemoryStore coreStore;

    public EnterpriseStore(JdbcTemplate jdbc, InMemoryStore coreStore) {
        this.jdbc = jdbc;
        this.coreStore = coreStore;
    }

    @PostConstruct
    void initialize() {
        createSchema();
        loadAll();
        ensureSeedData();
        ensureCompanyPolicyDefaults();
        ensureHouseholdSubject();
        ensureEntityTransferSeed();
        ensureAccessDefaults();
        attachDepartmentNames();
    }

    private void createSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS companies (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                entity_type TEXT NOT NULL DEFAULT 'company',
                credit_code TEXT,
                industry TEXT NOT NULL,
                taxpayer_type TEXT NOT NULL,
                currency TEXT NOT NULL,
                country TEXT NOT NULL DEFAULT '中国',
                province TEXT NOT NULL DEFAULT '',
                city TEXT NOT NULL DEFAULT '',
                district TEXT NOT NULL DEFAULT '',
                registered_address TEXT,
                operating_region TEXT NOT NULL DEFAULT '',
                tax_authority TEXT,
                policy_profile_key TEXT NOT NULL DEFAULT 'CN-DEFAULT-DEMO-POLICY',
                fiscal_year_start_month INTEGER NOT NULL DEFAULT 1,
                owner_id INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        ensureColumn("companies", "entity_type", "TEXT NOT NULL DEFAULT 'company'");
        ensureColumn("companies", "country", "TEXT NOT NULL DEFAULT '中国'");
        ensureColumn("companies", "province", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("companies", "city", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("companies", "district", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("companies", "registered_address", "TEXT");
        ensureColumn("companies", "operating_region", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("companies", "tax_authority", "TEXT");
        ensureColumn("companies", "policy_profile_key", "TEXT NOT NULL DEFAULT 'CN-DEFAULT-DEMO-POLICY'");
        ensureColumn("companies", "fiscal_year_start_month", "INTEGER NOT NULL DEFAULT 1");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS departments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                company_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                cost_center TEXT NOT NULL,
                manager_employee_id INTEGER,
                budget TEXT NOT NULL,
                status INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS employees (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                company_id INTEGER NOT NULL,
                user_id INTEGER,
                department_id INTEGER,
                name TEXT NOT NULL,
                email TEXT NOT NULL,
                phone TEXT,
                position TEXT NOT NULL,
                employment_type TEXT NOT NULL,
                status TEXT NOT NULL,
                access_role TEXT NOT NULL DEFAULT 'employee',
                access_scope TEXT NOT NULL DEFAULT 'self',
                hire_date TEXT NOT NULL,
                leave_date TEXT,
                salary TEXT NOT NULL,
                social_insurance TEXT NOT NULL,
                housing_fund TEXT NOT NULL,
                tax_estimate TEXT NOT NULL,
                monthly_cost TEXT NOT NULL,
                emergency_contact TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        ensureColumn("employees", "access_role", "TEXT NOT NULL DEFAULT 'employee'");
        ensureColumn("employees", "access_scope", "TEXT NOT NULL DEFAULT 'self'");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS employment_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                company_id INTEGER NOT NULL,
                employee_id INTEGER NOT NULL,
                type TEXT NOT NULL,
                effective_date TEXT NOT NULL,
                note TEXT NOT NULL,
                operator_user_id INTEGER NOT NULL,
                created_at TEXT NOT NULL
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS tax_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                company_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                period TEXT NOT NULL,
                tax_type TEXT NOT NULL,
                taxable_amount TEXT NOT NULL,
                tax_amount TEXT NOT NULL,
                paid_amount TEXT NOT NULL,
                due_date TEXT NOT NULL,
                status TEXT NOT NULL,
                note TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS entity_transfers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                from_entity_id INTEGER NOT NULL,
                to_entity_id INTEGER NOT NULL,
                transfer_type TEXT NOT NULL,
                amount TEXT NOT NULL,
                currency TEXT NOT NULL,
                transfer_date TEXT NOT NULL,
                note TEXT,
                status TEXT NOT NULL,
                operator_user_id INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
    }

    private void loadAll() {
        companies.clear();
        departments.clear();
        employees.clear();
        entityTransfers.clear();
        employmentEvents.clear();
        taxItems.clear();

        forEachRow("SELECT * FROM companies", rs -> companies.put(rs.getLong("id"), mapCompany(rs)));
        forEachRow("SELECT * FROM departments", rs -> departments.put(rs.getLong("id"), mapDepartment(rs)));
        forEachRow("SELECT * FROM employees", rs -> employees.put(rs.getLong("id"), mapEmployee(rs)));
        forEachRow("SELECT * FROM entity_transfers", rs -> entityTransfers.put(rs.getLong("id"), mapEntityTransfer(rs)));
        forEachRow("SELECT * FROM employment_events", rs -> employmentEvents.put(rs.getLong("id"), mapEmploymentEvent(rs)));
        forEachRow("SELECT * FROM tax_items", rs -> taxItems.put(rs.getLong("id"), mapTaxItem(rs)));
    }

    private void ensureColumn(String tableName, String columnName, String definition) {
        Boolean exists = jdbc.query("PRAGMA table_info(" + tableName + ")", rs -> {
            while (rs.next()) {
                if (columnName.equals(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        });
        if (Boolean.TRUE.equals(exists)) {
            return;
        }
        jdbc.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
    }

    private void ensureSeedData() {
        if (!companies.isEmpty()) {
            return;
        }
        User owner = coreStore.users.values().stream()
            .filter(user -> user.role == 1)
            .min(Comparator.comparing(user -> user.id))
            .or(() -> coreStore.users.values().stream().min(Comparator.comparing(user -> user.id)))
            .orElse(null);
        if (owner == null) {
            return;
        }

        Company company = company(owner.id, "深圳市示例电商科技有限公司", "DEMO-COMPANY-CREDIT-CODE", "软件与信息技术服务", "小规模纳税人", "CNY");
        Department management = department(company.id, "管理层", "CEO", "30000");
        Department finance = department(company.id, "财务行政", "FIN-ADMIN", "42000");
        Department product = department(company.id, "产品研发", "RND", "120000");
        Department sales = department(company.id, "市场销售", "SALES", "65000");

        Employee founder = employee(company.id, owner.id, management.id, owner.nickname, owner.email, "13800000001",
            "创始人 / CEO", "full_time", "active", "founder", "company", "2026-01-05", null,
            "28000", "4200", "3360", "2600", "38160", "李女士 13800000009");
        Optional<User> member = coreStore.users.values().stream()
            .filter(user -> user.id != owner.id)
            .min(Comparator.comparing(user -> user.id));
        member.ifPresent(user -> employee(company.id, user.id, finance.id, user.nickname, user.email, "13800000002",
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
        employee(company.id, null, sales.id, "吴青", "wu.qing@mamoji.local", "13800000006",
            "市场运营", "full_time", "departed", "viewer", "self", "2026-02-15", "2026-06-03",
            "14000", "2100", "1680", "500", "18280", "吴先生 13800000014");

        event(company.id, founder.id, "onboard", founder.hireDate, "公司创始人账号初始化", owner.id);
        employees.values().stream()
            .filter(employee -> employee.companyId == company.id && employee.id != founder.id)
            .forEach(employee -> event(company.id, employee.id, "onboard", employee.hireDate, "演示员工入职", owner.id));
        employees.values().stream()
            .filter(employee -> employee.companyId == company.id && "departed".equals(employee.status))
            .forEach(employee -> event(company.id, employee.id, "offboard", employee.leaveDate, "演示员工离职交接完成", owner.id));

        taxItem(company.id, "2026-06 增值税预估", "2026-06", "vat", "17800", "534", "0", "2026-07-15", "estimated", "按本月收入简化估算");
        taxItem(company.id, "2026-Q2 企业所得税预缴", "2026-Q2", "corporate_income_tax", "45200", "2260", "0", "2026-07-15", "pending", "按季度利润估算");
        taxItem(company.id, "2026-06 个税代扣代缴", "2026-06", "personal_income_tax", "74000", "6800", "1200", "2026-07-15", "pending", "按当前员工薪资样例估算");
        taxItem(company.id, "2026-06 附加税", "2026-06", "surcharge", "534", "64.08", "0", "2026-07-15", "estimated", "增值税附加简化估算");
    }

    private void ensureHouseholdSubject() {
        boolean hasHousehold = companies.values().stream().anyMatch(company -> "household".equals(company.entityType));
        if (hasHousehold) {
            return;
        }
        User owner = coreStore.users.values().stream()
            .filter(user -> user.role == 1)
            .min(Comparator.comparing(user -> user.id))
            .or(() -> coreStore.users.values().stream().min(Comparator.comparing(user -> user.id)))
            .orElse(null);
        if (owner == null) {
            return;
        }
        Company household = company(owner.id, "演示家庭资产主体", "household", null, "家庭资产管理", "非经营主体", "CNY");
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
        User owner = coreStore.users.values().stream()
            .filter(user -> user.role == 1)
            .min(Comparator.comparing(user -> user.id))
            .or(() -> coreStore.users.values().stream().min(Comparator.comparing(user -> user.id)))
            .orElse(null);
        if (owner == null) {
            return;
        }
        String currency = isBlank(company.get().currency) ? "CNY" : company.get().currency;
        entityTransfer(householdId, companyId, "shareholder_advance", "50000", currency, "2026-02-01",
            "家庭资金垫付公司启动备用金", "recorded", owner.id);
        entityTransfer(companyId, householdId, "advance_repayment", "12000", currency, "2026-04-15",
            "公司归还部分家庭垫资", "recorded", owner.id);
        entityTransfer(householdId, companyId, "expense_reimbursement", "2680", currency, "2026-05-08",
            "家庭账户代垫 SaaS 订阅和办公采购", "recorded", owner.id);
        entityTransfer(companyId, householdId, "reimbursement_payment", "2680", currency, "2026-05-20",
            "公司报销家庭代垫支出", "recorded", owner.id);
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
            if (company.city != null && company.city.contains("深圳") && "CN-DEFAULT-DEMO-POLICY".equals(company.policyProfileKey)) {
                company.policyProfileKey = "CN-GD-SZ-DEMO-POLICY";
                updated = true;
            } else if (isBlank(company.policyProfileKey)) {
                company.policyProfileKey = company.city != null && company.city.contains("深圳")
                    ? "CN-GD-SZ-DEMO-POLICY"
                    : "CN-DEFAULT-DEMO-POLICY";
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
        employees.values().forEach(employee -> {
            String role = employee.accessRole == null || employee.accessRole.isBlank() ? "employee" : employee.accessRole;
            String scope = employee.accessScope == null || employee.accessScope.isBlank() ? "self" : employee.accessScope;
            Optional<User> user = Optional.ofNullable(employee.userId).map(coreStore.users::get);
            if (user.map(candidate -> candidate.role == 1).orElse(false)) {
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

    public List<Company> sortedCompanies() {
        return companies.values().stream().sorted(Comparator.comparing(company -> company.id)).toList();
    }

    public List<Department> sortedDepartments(long companyId) {
        return departments.values().stream()
            .filter(department -> department.companyId == companyId)
            .sorted(Comparator.comparing(department -> department.id))
            .toList();
    }

    public List<Employee> sortedEmployees(long companyId) {
        attachDepartmentNames();
        return employees.values().stream()
            .filter(employee -> employee.companyId == companyId)
            .sorted(Comparator.comparing((Employee employee) -> employee.status.equals("departed")).thenComparing(employee -> employee.id))
            .toList();
    }

    public List<EmploymentEvent> sortedEmploymentEvents(long companyId) {
        return employmentEvents.values().stream()
            .filter(event -> event.companyId == companyId)
            .sorted(Comparator.comparing((EmploymentEvent event) -> event.effectiveDate).reversed().thenComparing(event -> event.id))
            .toList();
    }

    public List<TaxItem> sortedTaxItems(long companyId) {
        return taxItems.values().stream()
            .filter(item -> item.companyId == companyId)
            .sorted(Comparator.comparing((TaxItem item) -> item.dueDate).thenComparing(item -> item.id))
            .toList();
    }

    public List<EntityTransfer> sortedEntityTransfers(List<Long> accessibleEntityIds, Long entityId) {
        return entityTransfers.values().stream()
            .filter(transfer -> accessibleEntityIds.contains(transfer.fromEntityId) || accessibleEntityIds.contains(transfer.toEntityId))
            .filter(transfer -> entityId == null || transfer.fromEntityId == entityId || transfer.toEntityId == entityId)
            .sorted(Comparator.comparing((EntityTransfer transfer) -> transfer.transferDate).reversed().thenComparing(transfer -> transfer.id))
            .peek(this::attachEntityTransferNames)
            .toList();
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
        company.policyProfileKey = company.city.contains("深圳") ? "CN-GD-SZ-DEMO-POLICY" : "CN-DEFAULT-DEMO-POLICY";
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

    public Department department(long companyId, String name, String costCenter, String budget) {
        Department department = new Department();
        department.companyId = companyId;
        department.name = name;
        department.costCenter = costCenter == null ? "" : costCenter;
        department.budget = money(budget);
        department.status = 1;
        stamp(department);
        department.id = insert("""
            INSERT INTO departments (company_id, name, cost_center, manager_employee_id, budget, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindDepartment(ps, department));
        departments.put(department.id, department);
        return department;
    }

    public void saveDepartment(Department department) {
        departments.put(department.id, department);
        jdbc.update("""
            UPDATE departments SET company_id = ?, name = ?, cost_center = ?, manager_employee_id = ?, budget = ?, status = ?, updated_at = ?
            WHERE id = ?
            """, department.companyId, department.name, department.costCenter, department.managerEmployeeId,
            moneyText(department.budget), department.status, department.updatedAt, department.id);
        attachDepartmentNames();
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
        employee.monthlyCost = monthlyCost == null ? totalMonthlyCost(employee) : money(monthlyCost);
        employee.emergencyContact = emergencyContact;
        stamp(employee);
        employee.id = insert("""
            INSERT INTO employees (
                company_id, user_id, department_id, name, email, phone, position, employment_type, status,
                access_role, access_scope, hire_date, leave_date, salary, social_insurance, housing_fund, tax_estimate, monthly_cost,
                emergency_contact, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindEmployee(ps, employee));
        employees.put(employee.id, employee);
        attachDepartmentNames();
        return employee;
    }

    public void saveEmployee(Employee employee) {
        employee.monthlyCost = totalMonthlyCost(employee);
        employees.put(employee.id, employee);
        jdbc.update("""
            UPDATE employees SET company_id = ?, user_id = ?, department_id = ?, name = ?, email = ?, phone = ?,
                position = ?, employment_type = ?, status = ?, access_role = ?, access_scope = ?, hire_date = ?, leave_date = ?, salary = ?,
                social_insurance = ?, housing_fund = ?, tax_estimate = ?, monthly_cost = ?, emergency_contact = ?,
                updated_at = ?
            WHERE id = ?
            """, employee.companyId, employee.userId, employee.departmentId, employee.name, employee.email, employee.phone,
            employee.position, employee.employmentType, employee.status, employee.accessRole, employee.accessScope, employee.hireDate, employee.leaveDate,
            moneyText(employee.salary), moneyText(employee.socialInsurance), moneyText(employee.housingFund),
            moneyText(employee.taxEstimate), moneyText(employee.monthlyCost), employee.emergencyContact,
            employee.updatedAt, employee.id);
        attachDepartmentNames();
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

    public TaxItem taxItem(
        long companyId,
        String name,
        String period,
        String taxType,
        String taxableAmount,
        String taxAmount,
        String paidAmount,
        String dueDate,
        String status,
        String note
    ) {
        TaxItem item = new TaxItem();
        item.companyId = companyId;
        item.name = name;
        item.period = period;
        item.taxType = taxType;
        item.taxableAmount = money(taxableAmount);
        item.taxAmount = money(taxAmount);
        item.paidAmount = money(paidAmount);
        item.dueDate = dueDate;
        item.status = status;
        item.note = note;
        stamp(item);
        item.id = insert("""
            INSERT INTO tax_items (
                company_id, name, period, tax_type, taxable_amount, tax_amount, paid_amount, due_date, status, note, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindTaxItem(ps, item));
        taxItems.put(item.id, item);
        return item;
    }

    public void saveTaxItem(TaxItem item) {
        taxItems.put(item.id, item);
        jdbc.update("""
            UPDATE tax_items SET company_id = ?, name = ?, period = ?, tax_type = ?, taxable_amount = ?, tax_amount = ?,
                paid_amount = ?, due_date = ?, status = ?, note = ?, updated_at = ?
            WHERE id = ?
            """, item.companyId, item.name, item.period, item.taxType, moneyText(item.taxableAmount), moneyText(item.taxAmount),
            moneyText(item.paidAmount), item.dueDate, item.status, item.note, item.updatedAt, item.id);
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

    public void saveEntityTransfer(EntityTransfer transfer) {
        entityTransfers.put(transfer.id, transfer);
        jdbc.update("""
            UPDATE entity_transfers SET from_entity_id = ?, to_entity_id = ?, transfer_type = ?, amount = ?, currency = ?,
                transfer_date = ?, note = ?, status = ?, operator_user_id = ?, updated_at = ?
            WHERE id = ?
            """, transfer.fromEntityId, transfer.toEntityId, transfer.transferType, moneyText(transfer.amount), transfer.currency,
            transfer.transferDate, transfer.note, transfer.status, transfer.operatorUserId, transfer.updatedAt, transfer.id);
        attachEntityTransferNames(transfer);
    }

    public void attachDepartmentNames() {
        employees.values().forEach(employee -> employee.departmentName = Optional.ofNullable(employee.departmentId)
            .map(departments::get)
            .map(department -> department.name)
            .orElse(null));
    }

    private void attachEntityTransferNames(EntityTransfer transfer) {
        transfer.fromEntityName = Optional.ofNullable(companies.get(transfer.fromEntityId)).map(company -> company.name).orElse(null);
        transfer.toEntityName = Optional.ofNullable(companies.get(transfer.toEntityId)).map(company -> company.name).orElse(null);
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Department mapDepartment(ResultSet rs) throws SQLException {
        Department department = new Department();
        department.id = rs.getLong("id");
        department.companyId = rs.getLong("company_id");
        department.name = rs.getString("name");
        department.costCenter = rs.getString("cost_center");
        department.managerEmployeeId = nullableLong(rs, "manager_employee_id");
        department.budget = money(rs.getString("budget"));
        department.status = rs.getInt("status");
        department.createdAt = rs.getString("created_at");
        department.updatedAt = rs.getString("updated_at");
        return department;
    }

    private Employee mapEmployee(ResultSet rs) throws SQLException {
        Employee employee = new Employee();
        employee.id = rs.getLong("id");
        employee.companyId = rs.getLong("company_id");
        employee.userId = nullableLong(rs, "user_id");
        employee.departmentId = nullableLong(rs, "department_id");
        employee.name = rs.getString("name");
        employee.email = rs.getString("email");
        employee.phone = rs.getString("phone");
        employee.position = rs.getString("position");
        employee.employmentType = rs.getString("employment_type");
        employee.status = rs.getString("status");
        employee.accessRole = rs.getString("access_role");
        employee.accessScope = rs.getString("access_scope");
        employee.hireDate = rs.getString("hire_date");
        employee.leaveDate = rs.getString("leave_date");
        employee.salary = money(rs.getString("salary"));
        employee.socialInsurance = money(rs.getString("social_insurance"));
        employee.housingFund = money(rs.getString("housing_fund"));
        employee.taxEstimate = money(rs.getString("tax_estimate"));
        employee.monthlyCost = money(rs.getString("monthly_cost"));
        employee.emergencyContact = rs.getString("emergency_contact");
        employee.createdAt = rs.getString("created_at");
        employee.updatedAt = rs.getString("updated_at");
        return employee;
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

    private TaxItem mapTaxItem(ResultSet rs) throws SQLException {
        TaxItem item = new TaxItem();
        item.id = rs.getLong("id");
        item.companyId = rs.getLong("company_id");
        item.name = rs.getString("name");
        item.period = rs.getString("period");
        item.taxType = rs.getString("tax_type");
        item.taxableAmount = money(rs.getString("taxable_amount"));
        item.taxAmount = money(rs.getString("tax_amount"));
        item.paidAmount = money(rs.getString("paid_amount"));
        item.dueDate = rs.getString("due_date");
        item.status = rs.getString("status");
        item.note = rs.getString("note");
        item.createdAt = rs.getString("created_at");
        item.updatedAt = rs.getString("updated_at");
        return item;
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

    private void bindDepartment(PreparedStatement ps, Department department) throws SQLException {
        ps.setLong(1, department.companyId);
        ps.setString(2, department.name);
        ps.setString(3, department.costCenter);
        setLongOrNull(ps, 4, department.managerEmployeeId);
        ps.setString(5, moneyText(department.budget));
        ps.setInt(6, department.status);
        ps.setString(7, department.createdAt);
        ps.setString(8, department.updatedAt);
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
        ps.setString(15, moneyText(employee.socialInsurance));
        ps.setString(16, moneyText(employee.housingFund));
        ps.setString(17, moneyText(employee.taxEstimate));
        ps.setString(18, moneyText(employee.monthlyCost));
        ps.setString(19, employee.emergencyContact);
        ps.setString(20, employee.createdAt);
        ps.setString(21, employee.updatedAt);
    }

    private void bindTaxItem(PreparedStatement ps, TaxItem item) throws SQLException {
        ps.setLong(1, item.companyId);
        ps.setString(2, item.name);
        ps.setString(3, item.period);
        ps.setString(4, item.taxType);
        ps.setString(5, moneyText(item.taxableAmount));
        ps.setString(6, moneyText(item.taxAmount));
        ps.setString(7, moneyText(item.paidAmount));
        ps.setString(8, item.dueDate);
        ps.setString(9, item.status);
        ps.setString(10, item.note);
        ps.setString(11, item.createdAt);
        ps.setString(12, item.updatedAt);
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
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            binder.bind(ps);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("SQLite did not return a generated key");
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

    private static BigDecimal totalMonthlyCost(Employee employee) {
        return money(employee.salary).add(money(employee.socialInsurance)).add(money(employee.housingFund)).add(money(employee.taxEstimate));
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
