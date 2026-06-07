package com.mamoji.repository;

import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.Department;
import com.mamoji.domain.Models.Employee;
import com.mamoji.domain.Models.EntityTransfer;
import com.mamoji.domain.Models.EmploymentEvent;
import com.mamoji.domain.Models.ReceiptVoucher;
import com.mamoji.domain.Models.SocialInsuranceItem;
import com.mamoji.domain.Models.TaxItem;
import com.mamoji.domain.Models.User;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    public final Map<Long, ReceiptVoucher> receiptVouchers = new ConcurrentHashMap<>();

    private static final String DEFAULT_SOCIAL_INSURANCE_REGION = "深圳";
    private static final String DEFAULT_HUKOU_TYPE = "non_local";
    private static final String DEFAULT_MEDICAL_TIER = "tier1";
    private static final BigDecimal SHENZHEN_PENSION_MIN_BASE = new BigDecimal("4775");
    private static final BigDecimal SHENZHEN_PENSION_MAX_BASE = new BigDecimal("27549");
    private static final BigDecimal SHENZHEN_MEDICAL_MIN_BASE = new BigDecimal("6727");
    private static final BigDecimal SHENZHEN_MEDICAL_MAX_BASE = new BigDecimal("33633");
    private static final BigDecimal SHENZHEN_UNEMPLOYMENT_MIN_BASE = new BigDecimal("2520");
    private static final BigDecimal SHENZHEN_UNEMPLOYMENT_MAX_BASE = new BigDecimal("44265");
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
    private static final BigDecimal DEFAULT_HOUSING_FUND_RATE = new BigDecimal("8");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

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
        ensureTaxItemDefaults();
        ensureHouseholdSubject();
        ensureEntityTransferSeed();
        ensureReceiptVoucherSeed();
        ensureEmployeePayrollDefaults();
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
                social_insurance_base TEXT NOT NULL DEFAULT '0',
                social_insurance_personal_rate TEXT NOT NULL DEFAULT '0',
                social_insurance_company_rate TEXT NOT NULL DEFAULT '0',
                social_insurance_personal_amount TEXT NOT NULL DEFAULT '0',
                social_insurance_company_amount TEXT NOT NULL DEFAULT '0',
                housing_fund_base TEXT NOT NULL DEFAULT '0',
                housing_fund_personal_rate TEXT NOT NULL DEFAULT '0',
                housing_fund_company_rate TEXT NOT NULL DEFAULT '0',
                housing_fund_personal_amount TEXT NOT NULL DEFAULT '0',
                housing_fund_company_amount TEXT NOT NULL DEFAULT '0',
                personal_deduction TEXT NOT NULL DEFAULT '0',
                net_pay_estimate TEXT NOT NULL DEFAULT '0',
                social_insurance_region TEXT NOT NULL DEFAULT '深圳',
                hukou_type TEXT NOT NULL DEFAULT 'non_local',
                medical_tier TEXT NOT NULL DEFAULT 'tier1',
                pension_base TEXT NOT NULL DEFAULT '0',
                medical_base TEXT NOT NULL DEFAULT '0',
                unemployment_base TEXT NOT NULL DEFAULT '0',
                work_injury_base TEXT NOT NULL DEFAULT '0',
                maternity_base TEXT NOT NULL DEFAULT '0',
                work_injury_company_rate TEXT NOT NULL DEFAULT '0.2',
                social_insurance_policy_note TEXT,
                monthly_cost TEXT NOT NULL,
                emergency_contact TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        ensureColumn("employees", "access_role", "TEXT NOT NULL DEFAULT 'employee'");
        ensureColumn("employees", "access_scope", "TEXT NOT NULL DEFAULT 'self'");
        ensureColumn("employees", "social_insurance_base", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "social_insurance_personal_rate", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "social_insurance_company_rate", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "social_insurance_personal_amount", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "social_insurance_company_amount", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "housing_fund_base", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "housing_fund_personal_rate", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "housing_fund_company_rate", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "housing_fund_personal_amount", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "housing_fund_company_amount", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "personal_deduction", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "net_pay_estimate", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "social_insurance_region", "TEXT NOT NULL DEFAULT '深圳'");
        ensureColumn("employees", "hukou_type", "TEXT NOT NULL DEFAULT 'non_local'");
        ensureColumn("employees", "medical_tier", "TEXT NOT NULL DEFAULT 'tier1'");
        ensureColumn("employees", "pension_base", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "medical_base", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "unemployment_base", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "work_injury_base", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "maternity_base", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("employees", "work_injury_company_rate", "TEXT NOT NULL DEFAULT '0.2'");
        ensureColumn("employees", "social_insurance_policy_note", "TEXT");
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
                deductible_amount TEXT NOT NULL DEFAULT '0',
                tax_rate TEXT NOT NULL DEFAULT '0',
                due_date TEXT NOT NULL,
                status TEXT NOT NULL,
                filing_status TEXT NOT NULL DEFAULT 'not_started',
                payment_status TEXT NOT NULL DEFAULT 'unpaid',
                frequency TEXT NOT NULL DEFAULT 'monthly',
                declaration_date TEXT,
                payment_date TEXT,
                responsible_person TEXT,
                risk_level TEXT NOT NULL DEFAULT 'medium',
                policy_basis TEXT,
                source_type TEXT NOT NULL DEFAULT 'manual',
                note TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        ensureColumn("tax_items", "deductible_amount", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("tax_items", "tax_rate", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("tax_items", "filing_status", "TEXT NOT NULL DEFAULT 'not_started'");
        ensureColumn("tax_items", "payment_status", "TEXT NOT NULL DEFAULT 'unpaid'");
        ensureColumn("tax_items", "frequency", "TEXT NOT NULL DEFAULT 'monthly'");
        ensureColumn("tax_items", "declaration_date", "TEXT");
        ensureColumn("tax_items", "payment_date", "TEXT");
        ensureColumn("tax_items", "responsible_person", "TEXT");
        ensureColumn("tax_items", "risk_level", "TEXT NOT NULL DEFAULT 'medium'");
        ensureColumn("tax_items", "policy_basis", "TEXT");
        ensureColumn("tax_items", "source_type", "TEXT NOT NULL DEFAULT 'manual'");
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
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS receipt_vouchers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                company_id INTEGER NOT NULL,
                transaction_id INTEGER,
                voucher_no TEXT NOT NULL,
                title TEXT NOT NULL,
                voucher_type TEXT NOT NULL,
                direction TEXT NOT NULL,
                counterparty TEXT NOT NULL,
                amount TEXT NOT NULL,
                tax_amount TEXT NOT NULL,
                issue_date TEXT NOT NULL,
                due_date TEXT,
                status TEXT NOT NULL,
                file_name TEXT,
                file_size INTEGER NOT NULL,
                file_type TEXT,
                risk_level TEXT NOT NULL,
                note TEXT,
                operator_user_id INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        createIndexes();
    }

    private void createIndexes() {
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_companies_owner ON companies(owner_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_companies_entity_type ON companies(entity_type)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_departments_company ON departments(company_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_employees_company_status ON employees(company_id, status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_employees_user ON employees(user_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_employees_department ON employees(department_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_employment_events_company_date ON employment_events(company_id, effective_date)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tax_items_company_due_status ON tax_items(company_id, due_date, status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tax_items_company_type_period ON tax_items(company_id, tax_type, period)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tax_items_company_risk ON tax_items(company_id, risk_level)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_entity_transfers_from_date ON entity_transfers(from_entity_id, transfer_date)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_entity_transfers_to_date ON entity_transfers(to_entity_id, transfer_date)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_receipt_vouchers_company_issue ON receipt_vouchers(company_id, issue_date)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_receipt_vouchers_company_status ON receipt_vouchers(company_id, status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_receipt_vouchers_transaction ON receipt_vouchers(transaction_id)");
    }

    private void loadAll() {
        companies.clear();
        departments.clear();
        employees.clear();
        entityTransfers.clear();
        employmentEvents.clear();
        taxItems.clear();
        receiptVouchers.clear();

        forEachRow("SELECT * FROM companies", rs -> companies.put(rs.getLong("id"), mapCompany(rs)));
        forEachRow("SELECT * FROM departments", rs -> departments.put(rs.getLong("id"), mapDepartment(rs)));
        forEachRow("SELECT * FROM employees", rs -> employees.put(rs.getLong("id"), mapEmployee(rs)));
        forEachRow("SELECT * FROM entity_transfers", rs -> entityTransfers.put(rs.getLong("id"), mapEntityTransfer(rs)));
        forEachRow("SELECT * FROM employment_events", rs -> employmentEvents.put(rs.getLong("id"), mapEmploymentEvent(rs)));
        forEachRow("SELECT * FROM tax_items", rs -> taxItems.put(rs.getLong("id"), mapTaxItem(rs)));
        forEachRow("SELECT * FROM receipt_vouchers", rs -> receiptVouchers.put(rs.getLong("id"), mapReceiptVoucher(rs)));
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

    private void ensureReceiptVoucherSeed() {
        Optional<Company> company = companies.values().stream()
            .filter(candidate -> "company".equals(candidate.entityType))
            .min(Comparator.comparing(candidate -> candidate.id));
        if (company.isEmpty()) {
            return;
        }
        long companyId = company.get().id;
        if (receiptVouchers.values().stream().anyMatch(voucher -> voucher.companyId == companyId)) {
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
        receiptVoucher(companyId, null, "INV-202606-001", "客户项目回款销项发票", "sales_invoice", "income",
            "客户项目方", "2800", "0", "2026-06-05", "2026-07-15", "verified",
            "invoice-202606-001.pdf", 184320, "application/pdf", "low", "已与项目回款匹配", owner.id);
        receiptVoucher(companyId, null, "VAT-202606-012", "办公采购进项发票", "purchase_invoice", "expense",
            "办公用品供应商", "899", "26.97", "2026-06-03", null, "linked",
            "purchase-keyboard.jpg", 728436, "image/jpeg", "low", "可用于成本归档", owner.id);
        receiptVoucher(companyId, null, "BANK-202606-003", "银行回单-房租付款", "bank_slip", "expense",
            "联合办公空间", "3200", "0", "2026-06-05", null, "verified",
            "rent-bank-slip.png", 566214, "image/png", "medium", "待关联租金周期事项", owner.id);
        receiptVoucher(companyId, null, "REIM-202605-008", "家庭代垫报销凭证", "reimbursement", "expense",
            "家庭资产主体", "2680", "0", "2026-05-20", null, "archived",
            "reimbursement-advance.pdf", 245761, "application/pdf", "low", "与主体往来记录一致", owner.id);
        receiptVoucher(companyId, null, "CTR-202606-002", "SaaS 年度订阅合同付款证明", "contract", "expense",
            "SaaS 服务商", "7800", "0", "2026-06-01", "2026-06-30", "pending_review",
            null, 0, null, "high", "金额较大，需补充合同附件和付款回单", owner.id);
        receiptVoucher(companyId, null, "TAX-202607-001", "税费申报回执待补充", "tax_receipt", "expense",
            "税务机关", "8458.08", "0", "2026-07-15", "2026-07-15", "pending_review",
            null, 0, null, "medium", "待完成申报后上传回执", owner.id);
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

    private void ensureEmployeePayrollDefaults() {
        employees.values().forEach(employee -> {
            boolean updated = hydrateEmployeePayroll(employee);
            if (updated) {
                employee.updatedAt = InMemoryStore.now();
                saveEmployee(employee);
            }
        });
    }

    private void ensureTaxItemDefaults() {
        taxItems.values().forEach(item -> {
            boolean updated = hydrateTaxItemDefaults(item);
            String riskLevel = riskLevelFor(item);
            if (!riskLevel.equals(item.riskLevel)) {
                item.riskLevel = riskLevel;
                updated = true;
            }
            if (updated) {
                item.updatedAt = InMemoryStore.now();
                saveTaxItem(item);
            }
        });
    }

    private boolean hydrateTaxItemDefaults(TaxItem item) {
        boolean updated = false;
        if (item.taxableAmount == null) {
            item.taxableAmount = BigDecimal.ZERO;
            updated = true;
        }
        if (item.taxAmount == null) {
            item.taxAmount = BigDecimal.ZERO;
            updated = true;
        }
        if (item.paidAmount == null) {
            item.paidAmount = BigDecimal.ZERO;
            updated = true;
        }
        if (item.deductibleAmount == null) {
            item.deductibleAmount = BigDecimal.ZERO;
            updated = true;
        }
        if (item.taxRate == null || item.taxRate.compareTo(BigDecimal.ZERO) == 0 && item.taxableAmount.compareTo(BigDecimal.ZERO) > 0) {
            item.taxRate = inferredTaxRate(item);
            updated = true;
        }
        if (isBlank(item.status)) {
            item.status = paymentStatusFor(item).equals("paid") ? "paid" : "pending";
            updated = true;
        }
        if (isBlank(item.paymentStatus)) {
            item.paymentStatus = paymentStatusFor(item);
            updated = true;
        }
        if (isBlank(item.filingStatus)) {
            item.filingStatus = filingStatusFor(item.status);
            updated = true;
        }
        if (isBlank(item.frequency)) {
            item.frequency = frequencyFor(item.period);
            updated = true;
        }
        if (isBlank(item.responsiblePerson)) {
            item.responsiblePerson = "财务负责人";
            updated = true;
        }
        if (isBlank(item.riskLevel)) {
            item.riskLevel = riskLevelFor(item);
            updated = true;
        }
        if (isBlank(item.policyBasis)) {
            item.policyBasis = Optional.ofNullable(companies.get(item.companyId))
                .map(company -> company.policyProfileKey)
                .orElse("CN-DEFAULT-DEMO-POLICY");
            updated = true;
        }
        if (isBlank(item.sourceType)) {
            item.sourceType = "manual";
            updated = true;
        }
        return updated;
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
        return employees.values().stream()
            .filter(employee -> employee.companyId == companyId)
            .sorted(Comparator.comparing((Employee employee) -> employee.status.equals("departed")).thenComparing(employee -> employee.id))
            .peek(this::attachDepartmentName)
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
            .peek(item -> {
                hydrateTaxItemDefaults(item);
                item.paymentStatus = paymentStatusFor(item);
                item.riskLevel = riskLevelFor(item);
            })
            .toList();
    }

    public List<EntityTransfer> sortedEntityTransfers(List<Long> accessibleEntityIds, Long entityId) {
        Set<Long> accessible = new HashSet<>(accessibleEntityIds);
        return entityTransfers.values().stream()
            .filter(transfer -> accessible.contains(transfer.fromEntityId) || accessible.contains(transfer.toEntityId))
            .filter(transfer -> entityId == null || transfer.fromEntityId == entityId || transfer.toEntityId == entityId)
            .sorted(Comparator.comparing((EntityTransfer transfer) -> transfer.transferDate).reversed().thenComparing(transfer -> transfer.id))
            .peek(this::attachEntityTransferNames)
            .toList();
    }

    public List<ReceiptVoucher> sortedReceiptVouchers(long companyId) {
        return receiptVouchers.values().stream()
            .filter(voucher -> voucher.companyId == companyId)
            .sorted(Comparator.comparing((ReceiptVoucher voucher) -> voucher.issueDate).reversed().thenComparing(voucher -> voucher.id))
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
                social_insurance_base, social_insurance_personal_rate, social_insurance_company_rate, social_insurance_personal_amount,
                social_insurance_company_amount, housing_fund_base, housing_fund_personal_rate, housing_fund_company_rate,
                housing_fund_personal_amount, housing_fund_company_amount, personal_deduction, net_pay_estimate,
                social_insurance_region, hukou_type, medical_tier, pension_base, medical_base, unemployment_base, work_injury_base,
                maternity_base, work_injury_company_rate, social_insurance_policy_note, monthly_cost, emergency_contact, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindEmployee(ps, employee));
        employees.put(employee.id, employee);
        attachDepartmentNames();
        return employee;
    }

    public void saveEmployee(Employee employee) {
        hydrateEmployeePayroll(employee);
        employees.put(employee.id, employee);
        jdbc.update("""
            UPDATE employees SET company_id = ?, user_id = ?, department_id = ?, name = ?, email = ?, phone = ?,
                position = ?, employment_type = ?, status = ?, access_role = ?, access_scope = ?, hire_date = ?, leave_date = ?, salary = ?,
                social_insurance = ?, housing_fund = ?, tax_estimate = ?, social_insurance_base = ?, social_insurance_personal_rate = ?,
                social_insurance_company_rate = ?, social_insurance_personal_amount = ?, social_insurance_company_amount = ?,
                housing_fund_base = ?, housing_fund_personal_rate = ?, housing_fund_company_rate = ?, housing_fund_personal_amount = ?,
                housing_fund_company_amount = ?, personal_deduction = ?, net_pay_estimate = ?, social_insurance_region = ?,
                hukou_type = ?, medical_tier = ?, pension_base = ?, medical_base = ?, unemployment_base = ?, work_injury_base = ?,
                maternity_base = ?, work_injury_company_rate = ?, social_insurance_policy_note = ?, monthly_cost = ?, emergency_contact = ?,
                updated_at = ?
            WHERE id = ?
            """, employee.companyId, employee.userId, employee.departmentId, employee.name, employee.email, employee.phone,
            employee.position, employee.employmentType, employee.status, employee.accessRole, employee.accessScope, employee.hireDate, employee.leaveDate,
            moneyText(employee.salary), moneyText(employee.socialInsurance), moneyText(employee.housingFund),
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
        item.deductibleAmount = BigDecimal.ZERO;
        item.taxRate = inferredTaxRate(item);
        item.dueDate = dueDate;
        item.status = status;
        item.filingStatus = filingStatusFor(status);
        item.paymentStatus = paymentStatusFor(item);
        item.frequency = frequencyFor(period);
        item.declarationDate = null;
        item.paymentDate = item.paymentStatus.equals("paid") ? dueDate : null;
        item.responsiblePerson = "财务负责人";
        item.riskLevel = riskLevelFor(item);
        item.policyBasis = Optional.ofNullable(companies.get(companyId))
            .map(company -> company.policyProfileKey)
            .orElse("CN-DEFAULT-DEMO-POLICY");
        item.sourceType = "demo_estimate";
        item.note = note;
        stamp(item);
        item.id = insert("""
            INSERT INTO tax_items (
                company_id, name, period, tax_type, taxable_amount, tax_amount, paid_amount, deductible_amount, tax_rate,
                due_date, status, filing_status, payment_status, frequency, declaration_date, payment_date,
                responsible_person, risk_level, policy_basis, source_type, note, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindTaxItem(ps, item));
        taxItems.put(item.id, item);
        return item;
    }

    public void saveTaxItem(TaxItem item) {
        taxItems.put(item.id, item);
        jdbc.update("""
            UPDATE tax_items SET company_id = ?, name = ?, period = ?, tax_type = ?, taxable_amount = ?, tax_amount = ?,
                paid_amount = ?, deductible_amount = ?, tax_rate = ?, due_date = ?, status = ?, filing_status = ?,
                payment_status = ?, frequency = ?, declaration_date = ?, payment_date = ?, responsible_person = ?,
                risk_level = ?, policy_basis = ?, source_type = ?, note = ?, updated_at = ?
            WHERE id = ?
            """, item.companyId, item.name, item.period, item.taxType, moneyText(item.taxableAmount), moneyText(item.taxAmount),
            moneyText(item.paidAmount), moneyText(item.deductibleAmount), moneyText(item.taxRate), item.dueDate, item.status,
            item.filingStatus, item.paymentStatus, item.frequency, item.declarationDate, item.paymentDate, item.responsiblePerson,
            item.riskLevel, item.policyBasis, item.sourceType, item.note, item.updatedAt, item.id);
    }

    public void deleteTaxItem(long id) {
        taxItems.remove(id);
        jdbc.update("DELETE FROM tax_items WHERE id = ?", id);
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

    public ReceiptVoucher receiptVoucher(
        long companyId,
        Long transactionId,
        String voucherNo,
        String title,
        String voucherType,
        String direction,
        String counterparty,
        String amount,
        String taxAmount,
        String issueDate,
        String dueDate,
        String status,
        String fileName,
        long fileSize,
        String fileType,
        String riskLevel,
        String note,
        long operatorUserId
    ) {
        ReceiptVoucher voucher = new ReceiptVoucher();
        voucher.companyId = companyId;
        voucher.transactionId = transactionId;
        voucher.voucherNo = voucherNo;
        voucher.title = title;
        voucher.voucherType = voucherType;
        voucher.direction = direction;
        voucher.counterparty = counterparty;
        voucher.amount = money(amount);
        voucher.taxAmount = money(taxAmount);
        voucher.issueDate = issueDate == null || issueDate.isBlank() ? LocalDate.now().toString() : issueDate;
        voucher.dueDate = dueDate == null || dueDate.isBlank() ? null : dueDate;
        voucher.status = status == null || status.isBlank() ? "pending_review" : status;
        voucher.fileName = fileName;
        voucher.fileSize = fileSize;
        voucher.fileType = fileType;
        voucher.riskLevel = riskLevel == null || riskLevel.isBlank() ? "low" : riskLevel;
        voucher.note = note;
        voucher.operatorUserId = operatorUserId;
        stamp(voucher);
        voucher.id = insert("""
            INSERT INTO receipt_vouchers (
                company_id, transaction_id, voucher_no, title, voucher_type, direction, counterparty,
                amount, tax_amount, issue_date, due_date, status, file_name, file_size, file_type,
                risk_level, note, operator_user_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindReceiptVoucher(ps, voucher));
        receiptVouchers.put(voucher.id, voucher);
        return voucher;
    }

    public void saveReceiptVoucher(ReceiptVoucher voucher) {
        receiptVouchers.put(voucher.id, voucher);
        jdbc.update("""
            UPDATE receipt_vouchers SET company_id = ?, transaction_id = ?, voucher_no = ?, title = ?, voucher_type = ?,
                direction = ?, counterparty = ?, amount = ?, tax_amount = ?, issue_date = ?, due_date = ?, status = ?,
                file_name = ?, file_size = ?, file_type = ?, risk_level = ?, note = ?, operator_user_id = ?, updated_at = ?
            WHERE id = ?
            """, voucher.companyId, voucher.transactionId, voucher.voucherNo, voucher.title, voucher.voucherType,
            voucher.direction, voucher.counterparty, moneyText(voucher.amount), moneyText(voucher.taxAmount),
            voucher.issueDate, voucher.dueDate, voucher.status, voucher.fileName, voucher.fileSize, voucher.fileType,
            voucher.riskLevel, voucher.note, voucher.operatorUserId, voucher.updatedAt, voucher.id);
    }

    public void attachDepartmentNames() {
        employees.values().forEach(this::attachDepartmentName);
    }

    private void attachDepartmentName(Employee employee) {
        employee.departmentName = Optional.ofNullable(employee.departmentId)
            .map(departments::get)
            .map(department -> department.name)
            .orElse(null);
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

    private BigDecimal inferredTaxRate(TaxItem item) {
        BigDecimal taxableAmount = money(item.taxableAmount);
        if (taxableAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return money(item.taxAmount)
            .multiply(new BigDecimal("100"))
            .divide(taxableAmount, 2, RoundingMode.HALF_UP);
    }

    private String paymentStatusFor(TaxItem item) {
        BigDecimal taxAmount = money(item.taxAmount);
        BigDecimal paidAmount = money(item.paidAmount);
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

    private String riskLevelFor(TaxItem item) {
        BigDecimal unpaid = money(item.taxAmount).subtract(money(item.paidAmount));
        if (unpaid.compareTo(BigDecimal.ZERO) <= 0 || "paid".equals(item.status)) {
            return "low";
        }
        LocalDate dueDate = parseDate(item.dueDate).orElse(LocalDate.now());
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

    private Optional<LocalDate> parseDate(String value) {
        try {
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(LocalDate.parse(value));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
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
        item.deductibleAmount = money(rs.getString("deductible_amount"));
        item.taxRate = money(rs.getString("tax_rate"));
        item.dueDate = rs.getString("due_date");
        item.status = rs.getString("status");
        item.filingStatus = rs.getString("filing_status");
        item.paymentStatus = rs.getString("payment_status");
        item.frequency = rs.getString("frequency");
        item.declarationDate = rs.getString("declaration_date");
        item.paymentDate = rs.getString("payment_date");
        item.responsiblePerson = rs.getString("responsible_person");
        item.riskLevel = rs.getString("risk_level");
        item.policyBasis = rs.getString("policy_basis");
        item.sourceType = rs.getString("source_type");
        item.note = rs.getString("note");
        item.createdAt = rs.getString("created_at");
        item.updatedAt = rs.getString("updated_at");
        hydrateTaxItemDefaults(item);
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

    private ReceiptVoucher mapReceiptVoucher(ResultSet rs) throws SQLException {
        ReceiptVoucher voucher = new ReceiptVoucher();
        voucher.id = rs.getLong("id");
        voucher.companyId = rs.getLong("company_id");
        voucher.transactionId = nullableLong(rs, "transaction_id");
        voucher.voucherNo = rs.getString("voucher_no");
        voucher.title = rs.getString("title");
        voucher.voucherType = rs.getString("voucher_type");
        voucher.direction = rs.getString("direction");
        voucher.counterparty = rs.getString("counterparty");
        voucher.amount = money(rs.getString("amount"));
        voucher.taxAmount = money(rs.getString("tax_amount"));
        voucher.issueDate = rs.getString("issue_date");
        voucher.dueDate = rs.getString("due_date");
        voucher.status = rs.getString("status");
        voucher.fileName = rs.getString("file_name");
        voucher.fileSize = rs.getLong("file_size");
        voucher.fileType = rs.getString("file_type");
        voucher.riskLevel = rs.getString("risk_level");
        voucher.note = rs.getString("note");
        voucher.operatorUserId = rs.getLong("operator_user_id");
        voucher.createdAt = rs.getString("created_at");
        voucher.updatedAt = rs.getString("updated_at");
        return voucher;
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
        ps.setString(18, moneyText(employee.socialInsuranceBase));
        ps.setString(19, moneyText(employee.socialInsurancePersonalRate));
        ps.setString(20, moneyText(employee.socialInsuranceCompanyRate));
        ps.setString(21, moneyText(employee.socialInsurancePersonalAmount));
        ps.setString(22, moneyText(employee.socialInsuranceCompanyAmount));
        ps.setString(23, moneyText(employee.housingFundBase));
        ps.setString(24, moneyText(employee.housingFundPersonalRate));
        ps.setString(25, moneyText(employee.housingFundCompanyRate));
        ps.setString(26, moneyText(employee.housingFundPersonalAmount));
        ps.setString(27, moneyText(employee.housingFundCompanyAmount));
        ps.setString(28, moneyText(employee.personalDeduction));
        ps.setString(29, moneyText(employee.netPayEstimate));
        ps.setString(30, employee.socialInsuranceRegion);
        ps.setString(31, employee.hukouType);
        ps.setString(32, employee.medicalTier);
        ps.setString(33, moneyText(employee.pensionBase));
        ps.setString(34, moneyText(employee.medicalBase));
        ps.setString(35, moneyText(employee.unemploymentBase));
        ps.setString(36, moneyText(employee.workInjuryBase));
        ps.setString(37, moneyText(employee.maternityBase));
        ps.setString(38, moneyText(employee.workInjuryCompanyRate));
        ps.setString(39, employee.socialInsurancePolicyNote);
        ps.setString(40, moneyText(employee.monthlyCost));
        ps.setString(41, employee.emergencyContact);
        ps.setString(42, employee.createdAt);
        ps.setString(43, employee.updatedAt);
    }

    private void bindTaxItem(PreparedStatement ps, TaxItem item) throws SQLException {
        ps.setLong(1, item.companyId);
        ps.setString(2, item.name);
        ps.setString(3, item.period);
        ps.setString(4, item.taxType);
        ps.setString(5, moneyText(item.taxableAmount));
        ps.setString(6, moneyText(item.taxAmount));
        ps.setString(7, moneyText(item.paidAmount));
        ps.setString(8, moneyText(item.deductibleAmount));
        ps.setString(9, moneyText(item.taxRate));
        ps.setString(10, item.dueDate);
        ps.setString(11, item.status);
        ps.setString(12, item.filingStatus);
        ps.setString(13, item.paymentStatus);
        ps.setString(14, item.frequency);
        ps.setString(15, item.declarationDate);
        ps.setString(16, item.paymentDate);
        ps.setString(17, item.responsiblePerson);
        ps.setString(18, item.riskLevel);
        ps.setString(19, item.policyBasis);
        ps.setString(20, item.sourceType);
        ps.setString(21, item.note);
        ps.setString(22, item.createdAt);
        ps.setString(23, item.updatedAt);
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

    private void bindReceiptVoucher(PreparedStatement ps, ReceiptVoucher voucher) throws SQLException {
        ps.setLong(1, voucher.companyId);
        setLongOrNull(ps, 2, voucher.transactionId);
        ps.setString(3, voucher.voucherNo);
        ps.setString(4, voucher.title);
        ps.setString(5, voucher.voucherType);
        ps.setString(6, voucher.direction);
        ps.setString(7, voucher.counterparty);
        ps.setString(8, moneyText(voucher.amount));
        ps.setString(9, moneyText(voucher.taxAmount));
        ps.setString(10, voucher.issueDate);
        ps.setString(11, voucher.dueDate);
        ps.setString(12, voucher.status);
        ps.setString(13, voucher.fileName);
        ps.setLong(14, voucher.fileSize);
        ps.setString(15, voucher.fileType);
        ps.setString(16, voucher.riskLevel);
        ps.setString(17, voucher.note);
        ps.setLong(18, voucher.operatorUserId);
        ps.setString(19, voucher.createdAt);
        ps.setString(20, voucher.updatedAt);
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

        List<String> warnings = new ArrayList<>();
        BigDecimal pensionBase = boundedBase("养老保险", firstPositive(employee.pensionBase, firstPositive(employee.socialInsuranceBase, salary)),
            SHENZHEN_PENSION_MIN_BASE, SHENZHEN_PENSION_MAX_BASE, warnings);
        BigDecimal medicalBase = boundedBase("医疗保险", firstPositive(employee.medicalBase, firstPositive(employee.socialInsuranceBase, salary)),
            SHENZHEN_MEDICAL_MIN_BASE, SHENZHEN_MEDICAL_MAX_BASE, warnings);
        BigDecimal unemploymentBase = boundedBase("失业保险", firstPositive(employee.unemploymentBase, salary),
            SHENZHEN_UNEMPLOYMENT_MIN_BASE, SHENZHEN_UNEMPLOYMENT_MAX_BASE, warnings);
        BigDecimal maternityBase = boundedBase("生育保险", firstPositive(employee.maternityBase, medicalBase),
            SHENZHEN_MEDICAL_MIN_BASE, SHENZHEN_MEDICAL_MAX_BASE, warnings);
        BigDecimal workInjuryBase = max(firstPositive(employee.workInjuryBase, salary), SHENZHEN_UNEMPLOYMENT_MIN_BASE);
        BigDecimal workInjuryCompanyRate = isZeroOrLess(employee.workInjuryCompanyRate)
            ? DEFAULT_WORK_INJURY_COMPANY_RATE
            : money(employee.workInjuryCompanyRate);

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

        if (isZeroOrLess(employee.housingFundBase)) {
            employee.housingFundBase = salary;
            updated = true;
        }
        if (isZeroOrLess(employee.housingFundPersonalRate)) {
            employee.housingFundPersonalRate = DEFAULT_HOUSING_FUND_RATE;
            updated = true;
        }
        if (isZeroOrLess(employee.housingFundCompanyRate)) {
            employee.housingFundCompanyRate = percentageOf(firstPositive(employee.housingFundCompanyAmount, employee.housingFund),
                employee.housingFundBase, DEFAULT_HOUSING_FUND_RATE);
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
            "广东省级统筹八档行业基准费率，深圳 2024-07 起 0.2%-1.4%，个人不缴", "2024-07-01 起"
        ));

        BigDecimal socialPersonalAmount = socialPersonalAmount(socialInsuranceItems);
        BigDecimal socialCompanyAmount = socialCompanyAmount(socialInsuranceItems);
        BigDecimal housingPersonalAmount = contribution(employee.housingFundBase, employee.housingFundPersonalRate);
        BigDecimal housingCompanyAmount = contribution(employee.housingFundBase, employee.housingFundCompanyRate);
        BigDecimal monthlyCost = salary.add(socialCompanyAmount).add(housingCompanyAmount);
        BigDecimal netPayEstimate = salary
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
        return "深圳五险演示政策：养老 2025-07 至 2026-06 基数 4775-27549；医保/生育 2026 年基数 6727-33633；失业 2025-07 至 2026-06 基数 2520-44265；工伤按行业费率 0.2%-1.4%。";
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
