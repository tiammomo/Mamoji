package com.mamoji.repository;

import com.mamoji.common.Permissions;
import com.mamoji.common.Roles;
import com.mamoji.domain.Models.Account;
import com.mamoji.domain.Models.Budget;
import com.mamoji.domain.Models.Category;
import com.mamoji.domain.Models.Ledger;
import com.mamoji.domain.Models.LedgerMember;
import com.mamoji.domain.Models.RegistrationInvite;
import com.mamoji.domain.Models.RecurringItem;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.domain.Models.User;
import com.mamoji.service.support.PasswordHasher;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

@Component
public class InMemoryStore {
    public final Map<Long, User> users = new ConcurrentHashMap<>();
    public final Map<Long, Account> accounts = new ConcurrentHashMap<>();
    public final Map<Long, Category> categories = new ConcurrentHashMap<>();
    public final Map<Long, Budget> budgets = new ConcurrentHashMap<>();
    public final Map<Long, TransactionRecord> transactions = new ConcurrentHashMap<>();
    public final Map<Long, Ledger> ledgers = new ConcurrentHashMap<>();
    public final Map<Long, LedgerMember> ledgerMembers = new ConcurrentHashMap<>();
    public final Map<Long, RegistrationInvite> registrationInvites = new ConcurrentHashMap<>();
    public final Map<String, RecurringItem> recurringItems = new ConcurrentHashMap<>();
    private final Map<String, AuthSession> tokens = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;
    private final PasswordHasher passwordHasher;
    private final boolean schemaCompatibilityEnabled;
    private final String bootstrapMode;
    private final String bootstrapAdminEmail;
    private final String bootstrapAdminPassword;
    private final String bootstrapAdminNickname;

    public InMemoryStore(
        JdbcTemplate jdbc,
        PasswordHasher passwordHasher,
        @Value("${mamoji.schema.compatibility-enabled:false}") boolean schemaCompatibilityEnabled,
        @Value("${mamoji.bootstrap.mode:demo}") String bootstrapMode,
        @Value("${mamoji.bootstrap.admin-email:test@mamoji.com}") String bootstrapAdminEmail,
        @Value("${mamoji.bootstrap.admin-password:123456}") String bootstrapAdminPassword,
        @Value("${mamoji.bootstrap.admin-nickname:Mamoji 公司管理员}") String bootstrapAdminNickname
    ) {
        this.jdbc = jdbc;
        this.passwordHasher = passwordHasher;
        this.schemaCompatibilityEnabled = schemaCompatibilityEnabled;
        this.bootstrapMode = defaultIfBlank(bootstrapMode, "demo").toLowerCase(Locale.ROOT);
        this.bootstrapAdminEmail = defaultIfBlank(bootstrapAdminEmail, "test@mamoji.com");
        this.bootstrapAdminPassword = defaultIfBlank(bootstrapAdminPassword, "123456");
        this.bootstrapAdminNickname = defaultIfBlank(bootstrapAdminNickname, "Mamoji 公司管理员");
    }

    @PostConstruct
    void initialize() {
        createSchema();
        loadAll();
        if (users.isEmpty()) {
            seedInitialData();
        } else {
            refreshBudgetData();
        }
    }

    private void createSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id BIGSERIAL PRIMARY KEY,
                email TEXT NOT NULL UNIQUE,
                nickname TEXT NOT NULL,
                avatar TEXT NOT NULL,
                family_id INTEGER,
                role INTEGER NOT NULL,
                permissions INTEGER NOT NULL,
                password_hash TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS accounts (
                id BIGSERIAL PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                sub_type TEXT,
                bank TEXT,
                account_no TEXT,
                opening_bank TEXT,
                currency TEXT NOT NULL DEFAULT 'CNY',
                balance TEXT NOT NULL,
                available_balance TEXT NOT NULL DEFAULT '0',
                credit_limit TEXT NOT NULL DEFAULT '0',
                frozen_amount TEXT NOT NULL DEFAULT '0',
                include_in_net_worth INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                ledger_id INTEGER,
                status INTEGER NOT NULL,
                opened_at TEXT,
                last_reconciled_at TEXT,
                owner_name TEXT,
                purpose TEXT,
                reconciliation_status TEXT NOT NULL DEFAULT 'pending',
                risk_level TEXT NOT NULL DEFAULT 'low',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        ensureColumn("accounts", "account_no", "TEXT");
        ensureColumn("accounts", "opening_bank", "TEXT");
        ensureColumn("accounts", "currency", "TEXT NOT NULL DEFAULT 'CNY'");
        ensureColumn("accounts", "available_balance", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("accounts", "credit_limit", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("accounts", "frozen_amount", "TEXT NOT NULL DEFAULT '0'");
        ensureColumn("accounts", "opened_at", "TEXT");
        ensureColumn("accounts", "last_reconciled_at", "TEXT");
        ensureColumn("accounts", "owner_name", "TEXT");
        ensureColumn("accounts", "purpose", "TEXT");
        ensureColumn("accounts", "reconciliation_status", "TEXT NOT NULL DEFAULT 'pending'");
        ensureColumn("accounts", "risk_level", "TEXT NOT NULL DEFAULT 'low'");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS categories (
                id BIGSERIAL PRIMARY KEY,
                name TEXT NOT NULL,
                icon TEXT NOT NULL,
                color TEXT NOT NULL,
                type TEXT NOT NULL,
                user_id INTEGER NOT NULL,
                status INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS budgets (
                id BIGSERIAL PRIMARY KEY,
                name TEXT NOT NULL,
                amount TEXT NOT NULL,
                start_date TEXT NOT NULL,
                end_date TEXT NOT NULL,
                warning_threshold INTEGER NOT NULL,
                status INTEGER NOT NULL,
                spent TEXT NOT NULL,
                remaining_amount TEXT NOT NULL,
                usage_rate REAL NOT NULL,
                warning_reached INTEGER NOT NULL,
                risk_level TEXT NOT NULL,
                risk_message TEXT NOT NULL,
                user_id INTEGER NOT NULL,
                ledger_id INTEGER,
                category_id INTEGER,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS transactions (
                id BIGSERIAL PRIMARY KEY,
                user_id INTEGER NOT NULL,
                family_id INTEGER,
                type INTEGER NOT NULL,
                amount TEXT NOT NULL,
                category_id INTEGER NOT NULL,
                account_id INTEGER NOT NULL,
                date TEXT NOT NULL,
                note TEXT NOT NULL,
                original_transaction_id INTEGER,
                refunded_amount TEXT NOT NULL,
                is_refundable INTEGER NOT NULL,
                budget_id INTEGER,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ledgers (
                id BIGSERIAL PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                currency TEXT NOT NULL,
                owner_id INTEGER NOT NULL,
                is_default INTEGER NOT NULL,
                status INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ledger_members (
                id BIGSERIAL PRIMARY KEY,
                ledger_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                nickname TEXT,
                avatar TEXT,
                joined_at TEXT NOT NULL
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS recurring_items (
                id TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                type INTEGER NOT NULL,
                amount TEXT NOT NULL,
                frequency TEXT NOT NULL,
                interval_value INTEGER NOT NULL,
                day_of_week INTEGER,
                day_of_month INTEGER,
                month_of_year INTEGER,
                start_date TEXT NOT NULL,
                end_date TEXT,
                last_executed TEXT,
                next_execution TEXT NOT NULL,
                status INTEGER NOT NULL,
                execution_count INTEGER NOT NULL,
                note TEXT
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS auth_tokens (
                token TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                expires_at TEXT NOT NULL
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS registration_invites (
                id BIGSERIAL PRIMARY KEY,
                token TEXT NOT NULL UNIQUE,
                email TEXT NOT NULL,
                role INTEGER NOT NULL DEFAULT 2,
                permissions INTEGER NOT NULL DEFAULT 15,
                expires_at TEXT NOT NULL,
                accepted_at TEXT,
                accepted_user_id INTEGER,
                invited_by_user_id INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        createIndexes();
    }

    private void ensureColumn(String tableName, String columnName, String definition) {
        if (!schemaCompatibilityEnabled) {
            return;
        }
        boolean exists = !jdbc.queryForList("""
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = ?
              AND column_name = ?
            """, tableName, columnName).isEmpty();
        if (exists) {
            return;
        }
        jdbc.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
    }

    private void createIndexes() {
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_accounts_user_status ON accounts(user_id, status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_accounts_user_type ON accounts(user_id, type)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_accounts_user_reconciliation ON accounts(user_id, reconciliation_status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_accounts_user_risk ON accounts(user_id, risk_level)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_categories_user_type ON categories(user_id, type)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_budgets_user_status_dates ON budgets(user_id, status, start_date, end_date)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_budgets_category_dates ON budgets(category_id, start_date, end_date)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_transactions_user_date ON transactions(user_id, date)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_transactions_user_type_date ON transactions(user_id, type, date)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_transactions_category_date ON transactions(category_id, date)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_transactions_account_date ON transactions(account_id, date)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_transactions_budget ON transactions(budget_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ledgers_owner_default ON ledgers(owner_id, is_default)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ledger_members_ledger_user ON ledger_members(ledger_id, user_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_recurring_user_status_next ON recurring_items(user_id, status, next_execution)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_auth_tokens_user ON auth_tokens(user_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_auth_tokens_expires ON auth_tokens(expires_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_registration_invites_email ON registration_invites(email, accepted_at, expires_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_registration_invites_inviter ON registration_invites(invited_by_user_id, created_at)");
    }

    private void loadAll() {
        users.clear();
        accounts.clear();
        categories.clear();
        budgets.clear();
        transactions.clear();
        ledgers.clear();
        ledgerMembers.clear();
        registrationInvites.clear();
        recurringItems.clear();
        tokens.clear();

        forEachRow("SELECT * FROM users", rs -> users.put(rs.getLong("id"), mapUser(rs)));
        forEachRow("SELECT * FROM accounts", rs -> accounts.put(rs.getLong("id"), mapAccount(rs)));
        forEachRow("SELECT * FROM categories", rs -> categories.put(rs.getLong("id"), mapCategory(rs)));
        forEachRow("SELECT * FROM budgets", rs -> budgets.put(rs.getLong("id"), mapBudget(rs)));
        forEachRow("SELECT * FROM transactions", rs -> transactions.put(rs.getLong("id"), mapTransaction(rs)));
        forEachRow("SELECT * FROM ledgers", rs -> ledgers.put(rs.getLong("id"), mapLedger(rs)));
        forEachRow("SELECT * FROM ledger_members", rs -> ledgerMembers.put(rs.getLong("id"), mapLedgerMember(rs)));
        forEachRow("SELECT * FROM registration_invites", rs -> registrationInvites.put(rs.getLong("id"), mapRegistrationInvite(rs)));
        forEachRow("SELECT * FROM recurring_items", rs -> recurringItems.put(rs.getString("id"), mapRecurringItem(rs)));
        jdbc.query("SELECT * FROM auth_tokens WHERE expires_at > ?", (org.springframework.jdbc.core.RowCallbackHandler) rs -> tokens.put(
            rs.getString("token"),
            new AuthSession(rs.getLong("user_id"), rs.getString("expires_at"))
        ), now());

        budgets.values().forEach(this::attachCategory);
        transactions.values().forEach(this::attachTransactionRelations);
    }

    private void seedInitialData() {
        if ("bootstrap".equals(bootstrapMode)) {
            bootstrapAdmin();
            return;
        }
        seedDemoData();
    }

    private void bootstrapAdmin() {
        validateBootstrapAdmin();
        User admin = user(
            bootstrapAdminEmail,
            bootstrapAdminNickname,
            "😊|#3370ff",
            passwordHasher.hash(bootstrapAdminPassword),
            Roles.ADMIN,
            Permissions.ALL
        );
        Ledger defaultLedger = ledger(admin.id, "公司经营账本", "生产环境默认经营账本", "CNY", true);
        member(defaultLedger.id, admin.id, "owner");
    }

    void seedDemoData() {
        User testUser = user(
            bootstrapAdminEmail,
            bootstrapAdminNickname,
            "😊|#3370ff",
            passwordHasher.hash(bootstrapAdminPassword),
            Roles.ADMIN,
            Permissions.ALL
        );
        Ledger defaultLedger = ledger(testUser.id, "公司经营账本", "初创公司经营收入、成本、税费与预算", "CNY", true);
        member(defaultLedger.id, testUser.id, "owner");

        Category revenue = category(testUser.id, "主营业务收入", "💼", "#22c55e", "income");
        Category teamMeal = category(testUser.id, "团队餐饮", "🍜", "#f97316", "expense");
        Category travel = category(testUser.id, "差旅交通", "🚇", "#0ea5e9", "expense");
        Category procurement = category(testUser.id, "办公采购", "🛍️", "#a855f7", "expense");
        Category customerRefund = category(testUser.id, "客户退款", "↩", "#f43f5e", "expense");
        Category severance = category(testUser.id, "离职补偿", "HR", "#8b5cf6", "expense");
        category(testUser.id, "办公租赁", "🏢", "#6366f1", "expense");
        category(testUser.id, "税费", "🧾", "#ef4444", "expense");

        Account cash = account(testUser.id, defaultLedger.id, "公司现金备用金", "cash", "备用金", null, "1200");
        Account bank = account(testUser.id, defaultLedger.id, "公司基本户", "bank", "对公账户", "招商银行", "26300");
        account(testUser.id, defaultLedger.id, "企业信用卡", "credit", "信用卡", "招商银行", "1800");

        Budget monthlyBudget = budget(testUser.id, defaultLedger.id, null, "本月经营预算", "6000",
            LocalDate.now().withDayOfMonth(1).toString(), LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth()).toString(), 85);

        transaction(testUser.id, defaultLedger.id, 1, "15000", revenue.id, bank.id, LocalDate.now().minusDays(4).toString(), "客户项目回款");
        transaction(testUser.id, defaultLedger.id, 1, "22000", revenue.id, bank.id, LocalDate.now().minusDays(5).toString(), "项目交付待回款：ERP 二期验收，预计下月到账");
        transaction(testUser.id, defaultLedger.id, 2, "68.5", teamMeal.id, cash.id, LocalDate.now().minusDays(1).toString(), "团队工作餐");
        transaction(testUser.id, defaultLedger.id, 2, "25", travel.id, cash.id, LocalDate.now().minusDays(2).toString(), "市内交通");
        transaction(testUser.id, defaultLedger.id, 2, "899", procurement.id, bank.id, LocalDate.now().minusDays(3).toString(), "办公键盘和配件");
        transaction(testUser.id, defaultLedger.id, 2, "1200", customerRefund.id, bank.id, LocalDate.now().minusDays(2).toString(), "客户退款：交付范围调整，冲减收入");
        transaction(testUser.id, defaultLedger.id, 2, "18000", severance.id, bank.id, LocalDate.now().minusDays(6).toString(), "离职补偿：N+1 经济补偿");

        monthlyBudget.spent = new BigDecimal("992.5");
        recalculateBudget(monthlyBudget);
        saveBudget(monthlyBudget);

        RecurringItem officeRent = new RecurringItem();
        officeRent.id = UUID.randomUUID().toString();
        officeRent.userId = testUser.id;
        officeRent.name = "办公室租金";
        officeRent.type = 2;
        officeRent.amount = new BigDecimal("3200");
        officeRent.frequency = "monthly";
        officeRent.interval = 1;
        officeRent.dayOfMonth = 5;
        officeRent.startDate = LocalDate.now().withDayOfMonth(1).toString();
        officeRent.nextExecution = LocalDate.now().withDayOfMonth(Math.min(5, LocalDate.now().lengthOfMonth())).plusMonths(1).toString();
        officeRent.status = 1;
        officeRent.executionCount = 0;
        officeRent.note = "每月办公室租金";
        recurringItems.put(officeRent.id, officeRent);
        saveRecurring(officeRent);
    }

    private void validateBootstrapAdmin() {
        if (bootstrapAdminEmail == null || bootstrapAdminEmail.isBlank() || !bootstrapAdminEmail.contains("@")) {
            throw new IllegalStateException("MAMOJI_BOOTSTRAP_ADMIN_EMAIL must be a valid email in bootstrap mode");
        }
        if (bootstrapAdminPassword == null
            || bootstrapAdminPassword.length() < 12
            || "123456".equals(bootstrapAdminPassword)
            || bootstrapAdminPassword.toLowerCase(Locale.ROOT).contains("replace-with")) {
            throw new IllegalStateException("MAMOJI_BOOTSTRAP_ADMIN_PASSWORD must be replaced with a strong password in bootstrap mode");
        }
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public User user(String email, String nickname, String avatar, String password, int role, int permissions) {
        User user = new User();
        user.email = email;
        user.nickname = nickname;
        user.avatar = avatar == null ? "😊|#3370ff" : avatar;
        user.role = role;
        user.permissions = permissions;
        user.passwordHash = password;
        stamp(user);
        user.id = insert("""
            INSERT INTO users (email, nickname, avatar, family_id, role, permissions, password_hash, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindUserInsert(ps, user));
        users.put(user.id, user);
        return user;
    }

    public Account account(long userId, Long ledgerId, String name, String type, String subType, String bank, String balance) {
        Account account = new Account();
        account.userId = userId;
        account.ledgerId = ledgerId;
        account.name = name;
        account.type = type;
        account.subType = subType;
        account.bank = bank;
        account.accountNo = null;
        account.openingBank = bank;
        account.currency = "CNY";
        account.balance = money(balance);
        account.availableBalance = account.balance;
        account.creditLimit = "credit".equals(type) ? account.balance.abs().max(new BigDecimal("20000")) : BigDecimal.ZERO;
        account.frozenAmount = BigDecimal.ZERO;
        account.includeInNetWorth = true;
        account.status = 1;
        account.openedAt = LocalDate.now().minusMonths(6).toString();
        account.lastReconciledAt = LocalDate.now().minusDays("cash".equals(type) ? 8 : 2).toString();
        account.ownerName = "财务负责人";
        account.purpose = defaultAccountPurpose(account);
        account.reconciliationStatus = "cash".equals(type) ? "pending" : "reconciled";
        account.riskLevel = "low";
        stamp(account);
        account.id = insert("""
            INSERT INTO accounts (
                name, type, sub_type, bank, account_no, opening_bank, currency, balance, available_balance,
                credit_limit, frozen_amount, include_in_net_worth, user_id, ledger_id, status, opened_at,
                last_reconciled_at, owner_name, purpose, reconciliation_status, risk_level, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindAccountInsert(ps, account));
        accounts.put(account.id, account);
        return account;
    }

    public Category category(long userId, String name, String icon, String color, String type) {
        Category category = new Category();
        category.userId = userId;
        category.name = name;
        category.icon = icon;
        category.color = color;
        category.type = type;
        category.status = 1;
        stamp(category);
        category.id = insert("""
            INSERT INTO categories (name, icon, color, type, user_id, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindCategoryInsert(ps, category));
        categories.put(category.id, category);
        return category;
    }

    public Budget budget(long userId, Long ledgerId, Long categoryId, String name, String amount, String startDate, String endDate, int warningThreshold) {
        Budget budget = new Budget();
        budget.userId = userId;
        budget.ledgerId = ledgerId;
        budget.categoryId = categoryId;
        budget.name = name;
        budget.amount = money(amount);
        budget.startDate = startDate;
        budget.endDate = endDate;
        budget.warningThreshold = warningThreshold;
        budget.status = 1;
        budget.spent = BigDecimal.ZERO;
        attachCategory(budget);
        recalculateBudget(budget);
        stamp(budget);
        budget.id = insert("""
            INSERT INTO budgets (
                name, amount, start_date, end_date, warning_threshold, status, spent, remaining_amount,
                usage_rate, warning_reached, risk_level, risk_message, user_id, ledger_id, category_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindBudgetInsert(ps, budget));
        budgets.put(budget.id, budget);
        return budget;
    }

    public TransactionRecord transaction(long userId, Long ledgerId, int type, String amount, long categoryId, long accountId, String date, String note) {
        TransactionRecord tx = new TransactionRecord();
        tx.userId = userId;
        tx.familyId = ledgerId;
        tx.type = type;
        tx.amount = money(amount);
        tx.categoryId = categoryId;
        tx.accountId = accountId;
        tx.date = date;
        tx.note = note == null ? "" : note;
        tx.refundedAmount = BigDecimal.ZERO;
        tx.isRefundable = type == 2;
        attachTransactionRelations(tx);
        stamp(tx);
        tx.id = insert("""
            INSERT INTO transactions (
                user_id, family_id, type, amount, category_id, account_id, date, note,
                original_transaction_id, refunded_amount, is_refundable, budget_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindTransactionInsert(ps, tx));
        transactions.put(tx.id, tx);
        return tx;
    }

    public Ledger ledger(long ownerId, String name, String description, String currency, boolean isDefault) {
        Ledger ledger = new Ledger();
        ledger.ownerId = ownerId;
        ledger.name = name;
        ledger.description = description == null ? "" : description;
        ledger.currency = currency == null ? "CNY" : currency;
        ledger.isDefault = isDefault;
        ledger.status = 1;
        stamp(ledger);
        ledger.id = insert("""
            INSERT INTO ledgers (name, description, currency, owner_id, is_default, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindLedgerInsert(ps, ledger));
        ledgers.put(ledger.id, ledger);
        return ledger;
    }

    public LedgerMember member(long ledgerId, long userId, String role) {
        LedgerMember member = new LedgerMember();
        member.ledgerId = ledgerId;
        member.userId = userId;
        member.role = role;
        Optional.ofNullable(users.get(userId)).ifPresent(user -> {
            member.nickname = user.nickname;
            member.avatar = user.avatar;
        });
        member.joinedAt = now();
        member.id = insert("""
            INSERT INTO ledger_members (ledger_id, user_id, role, nickname, avatar, joined_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, ps -> bindLedgerMemberInsert(ps, member));
        ledgerMembers.put(member.id, member);
        return member;
    }

    public RegistrationInvite registrationInvite(
        String token,
        String email,
        int role,
        int permissions,
        String expiresAt,
        long invitedByUserId
    ) {
        RegistrationInvite invite = new RegistrationInvite();
        invite.token = token;
        invite.email = email == null ? "" : email.toLowerCase(Locale.ROOT);
        invite.role = role;
        invite.permissions = permissions;
        invite.expiresAt = expiresAt;
        invite.invitedByUserId = invitedByUserId;
        stamp(invite);
        invite.id = insert("""
            INSERT INTO registration_invites (
                token, email, role, permissions, expires_at, accepted_at, accepted_user_id, invited_by_user_id,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindRegistrationInvite(ps, invite));
        registrationInvites.put(invite.id, invite);
        return invite;
    }

    public Optional<RegistrationInvite> findRegistrationInviteByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return registrationInvites.values().stream()
            .filter(invite -> invite.token.equals(token))
            .findFirst();
    }

    public List<RegistrationInvite> sortedRegistrationInvites() {
        return registrationInvites.values().stream()
            .sorted(Comparator.comparing((RegistrationInvite invite) -> invite.createdAt).reversed()
                .thenComparing(Comparator.comparingLong((RegistrationInvite invite) -> invite.id).reversed()))
            .toList();
    }

    public void saveRegistrationInvite(RegistrationInvite invite) {
        registrationInvites.put(invite.id, invite);
        jdbc.update("""
            UPDATE registration_invites SET token = ?, email = ?, role = ?, permissions = ?, expires_at = ?,
                accepted_at = ?, accepted_user_id = ?, invited_by_user_id = ?, created_at = ?, updated_at = ?
            WHERE id = ?
            """, invite.token, invite.email, invite.role, invite.permissions, invite.expiresAt, invite.acceptedAt,
            invite.acceptedUserId, invite.invitedByUserId, invite.createdAt, invite.updatedAt, invite.id);
    }

    public void saveUser(User user) {
        users.put(user.id, user);
        jdbc.update("""
            UPDATE users SET email = ?, nickname = ?, avatar = ?, family_id = ?, role = ?, permissions = ?,
                password_hash = ?, created_at = ?, updated_at = ? WHERE id = ?
            """, user.email, user.nickname, user.avatar, user.familyId, user.role, user.permissions,
            user.passwordHash, user.createdAt, user.updatedAt, user.id);
    }

    public void saveAccount(Account account) {
        accounts.put(account.id, account);
        jdbc.update("""
            UPDATE accounts SET name = ?, type = ?, sub_type = ?, bank = ?, account_no = ?, opening_bank = ?, currency = ?,
                balance = ?, available_balance = ?, credit_limit = ?, frozen_amount = ?, include_in_net_worth = ?,
                user_id = ?, ledger_id = ?, status = ?, opened_at = ?, last_reconciled_at = ?, owner_name = ?,
                purpose = ?, reconciliation_status = ?, risk_level = ?, created_at = ?, updated_at = ? WHERE id = ?
            """, account.name, account.type, account.subType, account.bank, account.accountNo, account.openingBank,
            account.currency, moneyText(account.balance), moneyText(account.availableBalance), moneyText(account.creditLimit),
            moneyText(account.frozenAmount), intBool(account.includeInNetWorth), account.userId, account.ledgerId,
            account.status, account.openedAt, account.lastReconciledAt, account.ownerName, account.purpose,
            account.reconciliationStatus, account.riskLevel, account.createdAt, account.updatedAt, account.id);
    }

    public void saveCategory(Category category) {
        categories.put(category.id, category);
        jdbc.update("""
            UPDATE categories SET name = ?, icon = ?, color = ?, type = ?, user_id = ?, status = ?, created_at = ?, updated_at = ?
            WHERE id = ?
            """, category.name, category.icon, category.color, category.type, category.userId, category.status,
            category.createdAt, category.updatedAt, category.id);
    }

    public void saveBudget(Budget budget) {
        budgets.put(budget.id, budget);
        jdbc.update("""
            UPDATE budgets SET name = ?, amount = ?, start_date = ?, end_date = ?, warning_threshold = ?, status = ?,
                spent = ?, remaining_amount = ?, usage_rate = ?, warning_reached = ?, risk_level = ?, risk_message = ?,
                user_id = ?, ledger_id = ?, category_id = ?, created_at = ?, updated_at = ? WHERE id = ?
            """, budget.name, moneyText(budget.amount), budget.startDate, budget.endDate, budget.warningThreshold, budget.status,
            moneyText(budget.spent), moneyText(budget.remainingAmount), budget.usageRate, intBool(budget.warningReached),
            budget.riskLevel, budget.riskMessage, budget.userId, budget.ledgerId, budget.categoryId, budget.createdAt, budget.updatedAt, budget.id);
    }

    public void saveTransaction(TransactionRecord tx) {
        transactions.put(tx.id, tx);
        jdbc.update("""
            UPDATE transactions SET user_id = ?, family_id = ?, type = ?, amount = ?, category_id = ?, account_id = ?, date = ?,
                note = ?, original_transaction_id = ?, refunded_amount = ?, is_refundable = ?, budget_id = ?, created_at = ?, updated_at = ?
            WHERE id = ?
            """, tx.userId, tx.familyId, tx.type, moneyText(tx.amount), tx.categoryId, tx.accountId, tx.date, tx.note,
            tx.originalTransactionId, moneyText(tx.refundedAmount), intBool(tx.isRefundable), tx.budgetId, tx.createdAt, tx.updatedAt, tx.id);
    }

    public void saveRecurring(RecurringItem item) {
        recurringItems.put(item.id, item);
        jdbc.update("""
            INSERT INTO recurring_items (
                id, user_id, name, type, amount, frequency, interval_value, day_of_week, day_of_month,
                month_of_year, start_date, end_date, last_executed, next_execution, status, execution_count, note
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                user_id = excluded.user_id, name = excluded.name, type = excluded.type, amount = excluded.amount,
                frequency = excluded.frequency, interval_value = excluded.interval_value, day_of_week = excluded.day_of_week,
                day_of_month = excluded.day_of_month, month_of_year = excluded.month_of_year, start_date = excluded.start_date,
                end_date = excluded.end_date, last_executed = excluded.last_executed, next_execution = excluded.next_execution,
                status = excluded.status, execution_count = excluded.execution_count, note = excluded.note
            """, item.id, item.userId, item.name, item.type, moneyText(item.amount), item.frequency, item.interval,
            item.dayOfWeek, item.dayOfMonth, item.monthOfYear, item.startDate, item.endDate, item.lastExecuted,
            item.nextExecution, item.status, item.executionCount, item.note);
    }

    public void rememberToken(String token, long userId, String expiresAt) {
        tokens.put(token, new AuthSession(userId, expiresAt));
        jdbc.update("""
            INSERT INTO auth_tokens (token, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)
            ON CONFLICT(token) DO UPDATE SET user_id = excluded.user_id, created_at = excluded.created_at, expires_at = excluded.expires_at
            """, token, userId, now(), expiresAt);
    }

    public void revokeToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return;
        }
        String token = authorizationHeader.substring(7);
        tokens.remove(token);
        jdbc.update("DELETE FROM auth_tokens WHERE token = ?", token);
    }

    public void deleteAccount(long id) {
        accounts.remove(id);
        jdbc.update("DELETE FROM accounts WHERE id = ?", id);
    }

    public void deleteCategory(long id) {
        categories.remove(id);
        jdbc.update("DELETE FROM categories WHERE id = ?", id);
    }

    public void deleteBudget(long id) {
        budgets.remove(id);
        jdbc.update("DELETE FROM budgets WHERE id = ?", id);
    }

    public void deleteTransaction(long id) {
        transactions.remove(id);
        jdbc.update("DELETE FROM transactions WHERE id = ?", id);
    }

    public void deleteRecurring(String id) {
        recurringItems.remove(id);
        jdbc.update("DELETE FROM recurring_items WHERE id = ?", id);
    }

    public void deleteUser(long id) {
        users.remove(id);
        jdbc.update("DELETE FROM users WHERE id = ?", id);
    }

    public void deleteLedgerMember(long ledgerId, long userId) {
        ledgerMembers.values().removeIf(member -> member.ledgerId == ledgerId && member.userId == userId);
        jdbc.update("DELETE FROM ledger_members WHERE ledger_id = ? AND user_id = ?", ledgerId, userId);
    }

    public Optional<User> currentUser(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authorizationHeader.substring(7);
        AuthSession session = tokens.get(token);
        if (session == null || session.expired()) {
            tokens.remove(token);
            jdbc.update("DELETE FROM auth_tokens WHERE token = ?", token);
            return Optional.empty();
        }
        return Optional.ofNullable(users.get(session.userId()));
    }

    public Optional<User> findUserByEmail(String email) {
        return users.values().stream().filter(user -> user.email.equalsIgnoreCase(email)).findFirst();
    }

    public List<TransactionRecord> sortedTransactions() {
        return transactions.values().stream()
            .sorted(Comparator.comparing((TransactionRecord tx) -> tx.date).reversed().thenComparing(tx -> tx.id))
            .toList();
    }

    public List<Account> sortedAccounts() {
        return accounts.values().stream().sorted(Comparator.comparing(account -> account.id)).toList();
    }

    public List<Category> sortedCategories() {
        return categories.values().stream().sorted(Comparator.comparing(category -> category.id)).toList();
    }

    public List<Budget> sortedBudgets() {
        return budgets.values().stream().sorted(Comparator.comparing(budget -> budget.id)).toList();
    }

    public void attachBudgetData() {
        attachBudgetData(false);
    }

    public void refreshBudgetData() {
        attachBudgetData(true);
    }

    private void attachBudgetData(boolean persist) {
        List<TransactionRecord> expenseTransactions = transactions.values().stream()
            .filter(tx -> tx.type == 2)
            .toList();
        budgets.values().forEach(budget -> {
            BigDecimal previousSpent = budget.spent;
            BigDecimal previousRemaining = budget.remainingAmount;
            double previousUsageRate = budget.usageRate;
            boolean previousWarningReached = budget.warningReached;
            String previousRiskLevel = budget.riskLevel;
            String previousRiskMessage = budget.riskMessage;
            int previousStatus = budget.status;
            String previousCategoryName = budget.categoryName;
            String previousCategoryIcon = budget.categoryIcon;

            budget.spent = expenseTransactions.stream()
                .filter(tx -> tx.userId == budget.userId)
                .filter(tx -> budget.ledgerId == null || Objects.equals(budget.ledgerId, tx.familyId))
                .filter(tx -> budget.categoryId == null || budget.categoryId.equals(tx.categoryId))
                .filter(tx -> tx.date.compareTo(budget.startDate) >= 0 && tx.date.compareTo(budget.endDate) <= 0)
                .map(tx -> tx.amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            attachCategory(budget);
            recalculateBudget(budget);
            if (persist && budgetComputedDataChanged(
                budget,
                previousSpent,
                previousRemaining,
                previousUsageRate,
                previousWarningReached,
                previousRiskLevel,
                previousRiskMessage,
                previousStatus,
                previousCategoryName,
                previousCategoryIcon
            )) {
                saveBudget(budget);
            }
        });
    }

    public void attachTransactionRelations(TransactionRecord tx) {
        Optional.ofNullable(categories.get(tx.categoryId)).ifPresent(category -> {
            tx.categoryName = category.name;
            tx.categoryIcon = category.icon;
            tx.categoryColor = category.color;
        });
        Optional.ofNullable(accounts.get(tx.accountId)).ifPresent(account -> tx.accountName = account.name);
    }

    public void attachCategory(Budget budget) {
        if (budget.categoryId == null) {
            budget.categoryName = null;
            budget.categoryIcon = null;
            return;
        }
        Optional.ofNullable(categories.get(budget.categoryId)).ifPresent(category -> {
            budget.categoryName = category.name;
            budget.categoryIcon = category.icon;
        });
    }

    public void recalculateBudget(Budget budget) {
        if (budget.amount == null || budget.amount.compareTo(BigDecimal.ZERO) <= 0) {
            budget.remainingAmount = BigDecimal.ZERO;
            budget.usageRate = 0;
        } else {
            budget.remainingAmount = budget.amount.subtract(nullToZero(budget.spent));
            budget.usageRate = nullToZero(budget.spent).divide(budget.amount, 4, java.math.RoundingMode.HALF_UP).doubleValue();
        }
        budget.warningReached = budget.usageRate * 100 >= budget.warningThreshold;
        if (budget.usageRate >= 1) {
            budget.riskLevel = "critical";
            budget.riskMessage = "预算已超支";
            budget.status = 3;
        } else if (budget.usageRate * 100 >= budget.warningThreshold) {
            budget.riskLevel = "high";
            budget.riskMessage = "接近预算上限";
        } else if (budget.usageRate >= 0.6) {
            budget.riskLevel = "medium";
            budget.riskMessage = "使用进度正常偏高";
        } else {
            budget.riskLevel = "low";
            budget.riskMessage = "预算健康";
        }
    }

    private boolean budgetComputedDataChanged(
        Budget budget,
        BigDecimal previousSpent,
        BigDecimal previousRemaining,
        double previousUsageRate,
        boolean previousWarningReached,
        String previousRiskLevel,
        String previousRiskMessage,
        int previousStatus,
        String previousCategoryName,
        String previousCategoryIcon
    ) {
        return !sameMoney(previousSpent, budget.spent)
            || !sameMoney(previousRemaining, budget.remainingAmount)
            || Double.compare(previousUsageRate, budget.usageRate) != 0
            || previousWarningReached != budget.warningReached
            || previousStatus != budget.status
            || !Objects.equals(previousRiskLevel, budget.riskLevel)
            || !Objects.equals(previousRiskMessage, budget.riskMessage)
            || !Objects.equals(previousCategoryName, budget.categoryName)
            || !Objects.equals(previousCategoryIcon, budget.categoryIcon);
    }

    private boolean sameMoney(BigDecimal left, BigDecimal right) {
        return nullToZero(left).compareTo(nullToZero(right)) == 0;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("users", new ArrayList<>(users.values()));
        data.put("accounts", sortedAccounts());
        data.put("categories", sortedCategories());
        data.put("transactions", sortedTransactions());
        data.put("budgets", sortedBudgets());
        data.put("ledgers", new ArrayList<>(ledgers.values()));
        data.put("recurring", new ArrayList<>(recurringItems.values()));
        return data;
    }

    private User mapUser(ResultSet rs) throws SQLException {
        User user = new User();
        user.id = rs.getLong("id");
        user.email = rs.getString("email");
        user.nickname = rs.getString("nickname");
        user.avatar = rs.getString("avatar");
        user.familyId = nullableLong(rs, "family_id");
        user.role = rs.getInt("role");
        user.permissions = rs.getInt("permissions");
        user.passwordHash = rs.getString("password_hash");
        user.createdAt = rs.getString("created_at");
        user.updatedAt = rs.getString("updated_at");
        return user;
    }

    private Account mapAccount(ResultSet rs) throws SQLException {
        Account account = new Account();
        account.id = rs.getLong("id");
        account.name = rs.getString("name");
        account.type = rs.getString("type");
        account.subType = rs.getString("sub_type");
        account.bank = rs.getString("bank");
        account.accountNo = rs.getString("account_no");
        account.openingBank = rs.getString("opening_bank");
        account.currency = textOr(rs.getString("currency"), "CNY");
        account.balance = money(rs.getString("balance"));
        account.availableBalance = money(rs.getString("available_balance"));
        account.creditLimit = money(rs.getString("credit_limit"));
        account.frozenAmount = money(rs.getString("frozen_amount"));
        account.includeInNetWorth = rs.getInt("include_in_net_worth") == 1;
        account.userId = rs.getLong("user_id");
        account.ledgerId = nullableLong(rs, "ledger_id");
        account.status = rs.getInt("status");
        account.openedAt = rs.getString("opened_at");
        account.lastReconciledAt = rs.getString("last_reconciled_at");
        account.ownerName = rs.getString("owner_name");
        account.purpose = rs.getString("purpose");
        account.reconciliationStatus = textOr(rs.getString("reconciliation_status"), "pending");
        account.riskLevel = textOr(rs.getString("risk_level"), "low");
        account.createdAt = rs.getString("created_at");
        account.updatedAt = rs.getString("updated_at");
        hydrateAccountDefaults(account);
        return account;
    }

    private Category mapCategory(ResultSet rs) throws SQLException {
        Category category = new Category();
        category.id = rs.getLong("id");
        category.name = rs.getString("name");
        category.icon = rs.getString("icon");
        category.color = rs.getString("color");
        category.type = rs.getString("type");
        category.userId = rs.getLong("user_id");
        category.status = rs.getInt("status");
        category.createdAt = rs.getString("created_at");
        category.updatedAt = rs.getString("updated_at");
        return category;
    }

    private Budget mapBudget(ResultSet rs) throws SQLException {
        Budget budget = new Budget();
        budget.id = rs.getLong("id");
        budget.name = rs.getString("name");
        budget.amount = money(rs.getString("amount"));
        budget.startDate = rs.getString("start_date");
        budget.endDate = rs.getString("end_date");
        budget.warningThreshold = rs.getInt("warning_threshold");
        budget.status = rs.getInt("status");
        budget.spent = money(rs.getString("spent"));
        budget.remainingAmount = money(rs.getString("remaining_amount"));
        budget.usageRate = rs.getDouble("usage_rate");
        budget.warningReached = rs.getInt("warning_reached") == 1;
        budget.riskLevel = rs.getString("risk_level");
        budget.riskMessage = rs.getString("risk_message");
        budget.userId = rs.getLong("user_id");
        budget.ledgerId = nullableLong(rs, "ledger_id");
        budget.categoryId = nullableLong(rs, "category_id");
        budget.createdAt = rs.getString("created_at");
        budget.updatedAt = rs.getString("updated_at");
        return budget;
    }

    private TransactionRecord mapTransaction(ResultSet rs) throws SQLException {
        TransactionRecord tx = new TransactionRecord();
        tx.id = rs.getLong("id");
        tx.userId = rs.getLong("user_id");
        tx.familyId = nullableLong(rs, "family_id");
        tx.type = rs.getInt("type");
        tx.amount = money(rs.getString("amount"));
        tx.categoryId = rs.getLong("category_id");
        tx.accountId = rs.getLong("account_id");
        tx.date = rs.getString("date");
        tx.note = rs.getString("note");
        tx.originalTransactionId = nullableLong(rs, "original_transaction_id");
        tx.refundedAmount = money(rs.getString("refunded_amount"));
        tx.isRefundable = rs.getInt("is_refundable") == 1;
        tx.budgetId = nullableLong(rs, "budget_id");
        tx.createdAt = rs.getString("created_at");
        tx.updatedAt = rs.getString("updated_at");
        return tx;
    }

    private Ledger mapLedger(ResultSet rs) throws SQLException {
        Ledger ledger = new Ledger();
        ledger.id = rs.getLong("id");
        ledger.name = rs.getString("name");
        ledger.description = rs.getString("description");
        ledger.currency = rs.getString("currency");
        ledger.ownerId = rs.getLong("owner_id");
        ledger.isDefault = rs.getInt("is_default") == 1;
        ledger.status = rs.getInt("status");
        ledger.createdAt = rs.getString("created_at");
        ledger.updatedAt = rs.getString("updated_at");
        return ledger;
    }

    private LedgerMember mapLedgerMember(ResultSet rs) throws SQLException {
        LedgerMember member = new LedgerMember();
        member.id = rs.getLong("id");
        member.ledgerId = rs.getLong("ledger_id");
        member.userId = rs.getLong("user_id");
        member.role = rs.getString("role");
        member.nickname = rs.getString("nickname");
        member.avatar = rs.getString("avatar");
        member.joinedAt = rs.getString("joined_at");
        return member;
    }

    private RegistrationInvite mapRegistrationInvite(ResultSet rs) throws SQLException {
        RegistrationInvite invite = new RegistrationInvite();
        invite.id = rs.getLong("id");
        invite.token = rs.getString("token");
        invite.email = rs.getString("email");
        invite.role = rs.getInt("role");
        invite.permissions = rs.getInt("permissions");
        invite.expiresAt = rs.getString("expires_at");
        invite.acceptedAt = rs.getString("accepted_at");
        invite.acceptedUserId = nullableLong(rs, "accepted_user_id");
        invite.invitedByUserId = rs.getLong("invited_by_user_id");
        invite.createdAt = rs.getString("created_at");
        invite.updatedAt = rs.getString("updated_at");
        return invite;
    }

    private RecurringItem mapRecurringItem(ResultSet rs) throws SQLException {
        RecurringItem item = new RecurringItem();
        item.id = rs.getString("id");
        item.userId = rs.getLong("user_id");
        item.name = rs.getString("name");
        item.type = rs.getInt("type");
        item.amount = money(rs.getString("amount"));
        item.frequency = rs.getString("frequency");
        item.interval = rs.getInt("interval_value");
        item.dayOfWeek = nullableInt(rs, "day_of_week");
        item.dayOfMonth = nullableInt(rs, "day_of_month");
        item.monthOfYear = nullableInt(rs, "month_of_year");
        item.startDate = rs.getString("start_date");
        item.endDate = rs.getString("end_date");
        item.lastExecuted = rs.getString("last_executed");
        item.nextExecution = rs.getString("next_execution");
        item.status = rs.getInt("status");
        item.executionCount = rs.getInt("execution_count");
        item.note = rs.getString("note");
        return item;
    }

    private void bindUserInsert(PreparedStatement ps, User user) throws SQLException {
        ps.setString(1, user.email);
        ps.setString(2, user.nickname);
        ps.setString(3, user.avatar);
        setLongOrNull(ps, 4, user.familyId);
        ps.setInt(5, user.role);
        ps.setInt(6, user.permissions);
        ps.setString(7, user.passwordHash);
        ps.setString(8, user.createdAt);
        ps.setString(9, user.updatedAt);
    }

    private static void hydrateAccountDefaults(Account account) {
        account.currency = textOr(account.currency, "CNY");
        account.openingBank = textOr(account.openingBank, account.bank);
        account.ownerName = textOr(account.ownerName, "财务负责人");
        account.purpose = textOr(account.purpose, defaultAccountPurpose(account));
        account.reconciliationStatus = textOr(account.reconciliationStatus, "pending");
        account.riskLevel = textOr(account.riskLevel, "low");
        account.openedAt = textOr(account.openedAt, LocalDate.now().minusMonths(6).toString());
        account.lastReconciledAt = textOr(account.lastReconciledAt, "cash".equals(account.type) ? null : LocalDate.now().minusDays(2).toString());
        account.creditLimit = nullToZero(account.creditLimit);
        account.frozenAmount = nullToZero(account.frozenAmount);
        if ("credit".equals(account.type) && account.creditLimit.compareTo(BigDecimal.ZERO) == 0) {
            account.creditLimit = account.balance.abs().max(new BigDecimal("20000"));
        }
        if (account.availableBalance == null || account.availableBalance.compareTo(BigDecimal.ZERO) == 0 && account.balance.compareTo(BigDecimal.ZERO) != 0) {
            account.availableBalance = "credit".equals(account.type)
                ? account.creditLimit.subtract(account.balance.abs()).subtract(account.frozenAmount).max(BigDecimal.ZERO)
                : account.balance.subtract(account.frozenAmount);
        }
        account.monthlyIncome = BigDecimal.ZERO;
        account.monthlyExpense = BigDecimal.ZERO;
        account.currentMonthNetFlow = BigDecimal.ZERO;
    }

    private static String defaultAccountPurpose(Account account) {
        return switch (account.type) {
            case "cash" -> "零星备用金和小额报销";
            case "bank" -> "客户回款、供应商付款和税费缴纳";
            case "credit" -> "短期周转和线上订阅付款";
            case "digital" -> "线上支付和平台收款";
            case "investment" -> "闲置资金理财和收益管理";
            case "debt" -> "借款、垫资和负债管理";
            default -> "企业资金账户";
        };
    }

    private void bindAccountInsert(PreparedStatement ps, Account account) throws SQLException {
        ps.setString(1, account.name);
        ps.setString(2, account.type);
        ps.setString(3, account.subType);
        ps.setString(4, account.bank);
        ps.setString(5, account.accountNo);
        ps.setString(6, account.openingBank);
        ps.setString(7, account.currency);
        ps.setString(8, moneyText(account.balance));
        ps.setString(9, moneyText(account.availableBalance));
        ps.setString(10, moneyText(account.creditLimit));
        ps.setString(11, moneyText(account.frozenAmount));
        ps.setInt(12, intBool(account.includeInNetWorth));
        ps.setLong(13, account.userId);
        setLongOrNull(ps, 14, account.ledgerId);
        ps.setInt(15, account.status);
        ps.setString(16, account.openedAt);
        ps.setString(17, account.lastReconciledAt);
        ps.setString(18, account.ownerName);
        ps.setString(19, account.purpose);
        ps.setString(20, account.reconciliationStatus);
        ps.setString(21, account.riskLevel);
        ps.setString(22, account.createdAt);
        ps.setString(23, account.updatedAt);
    }

    private void bindCategoryInsert(PreparedStatement ps, Category category) throws SQLException {
        ps.setString(1, category.name);
        ps.setString(2, category.icon);
        ps.setString(3, category.color);
        ps.setString(4, category.type);
        ps.setLong(5, category.userId);
        ps.setInt(6, category.status);
        ps.setString(7, category.createdAt);
        ps.setString(8, category.updatedAt);
    }

    private void bindBudgetInsert(PreparedStatement ps, Budget budget) throws SQLException {
        ps.setString(1, budget.name);
        ps.setString(2, moneyText(budget.amount));
        ps.setString(3, budget.startDate);
        ps.setString(4, budget.endDate);
        ps.setInt(5, budget.warningThreshold);
        ps.setInt(6, budget.status);
        ps.setString(7, moneyText(budget.spent));
        ps.setString(8, moneyText(budget.remainingAmount));
        ps.setDouble(9, budget.usageRate);
        ps.setInt(10, intBool(budget.warningReached));
        ps.setString(11, budget.riskLevel);
        ps.setString(12, budget.riskMessage);
        ps.setLong(13, budget.userId);
        setLongOrNull(ps, 14, budget.ledgerId);
        setLongOrNull(ps, 15, budget.categoryId);
        ps.setString(16, budget.createdAt);
        ps.setString(17, budget.updatedAt);
    }

    private void bindTransactionInsert(PreparedStatement ps, TransactionRecord tx) throws SQLException {
        ps.setLong(1, tx.userId);
        setLongOrNull(ps, 2, tx.familyId);
        ps.setInt(3, tx.type);
        ps.setString(4, moneyText(tx.amount));
        ps.setLong(5, tx.categoryId);
        ps.setLong(6, tx.accountId);
        ps.setString(7, tx.date);
        ps.setString(8, tx.note);
        setLongOrNull(ps, 9, tx.originalTransactionId);
        ps.setString(10, moneyText(tx.refundedAmount));
        ps.setInt(11, intBool(tx.isRefundable));
        setLongOrNull(ps, 12, tx.budgetId);
        ps.setString(13, tx.createdAt);
        ps.setString(14, tx.updatedAt);
    }

    private void bindLedgerInsert(PreparedStatement ps, Ledger ledger) throws SQLException {
        ps.setString(1, ledger.name);
        ps.setString(2, ledger.description);
        ps.setString(3, ledger.currency);
        ps.setLong(4, ledger.ownerId);
        ps.setInt(5, intBool(ledger.isDefault));
        ps.setInt(6, ledger.status);
        ps.setString(7, ledger.createdAt);
        ps.setString(8, ledger.updatedAt);
    }

    private void bindLedgerMemberInsert(PreparedStatement ps, LedgerMember member) throws SQLException {
        ps.setLong(1, member.ledgerId);
        ps.setLong(2, member.userId);
        ps.setString(3, member.role);
        ps.setString(4, member.nickname);
        ps.setString(5, member.avatar);
        ps.setString(6, member.joinedAt);
    }

    private void bindRegistrationInvite(PreparedStatement ps, RegistrationInvite invite) throws SQLException {
        ps.setString(1, invite.token);
        ps.setString(2, invite.email);
        ps.setInt(3, invite.role);
        ps.setInt(4, invite.permissions);
        ps.setString(5, invite.expiresAt);
        ps.setString(6, invite.acceptedAt);
        setLongOrNull(ps, 7, invite.acceptedUserId);
        ps.setLong(8, invite.invitedByUserId);
        ps.setString(9, invite.createdAt);
        ps.setString(10, invite.updatedAt);
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

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }

    private static void setLongOrNull(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setLong(index, value);
        }
    }

    private static int intBool(boolean value) {
        return value ? 1 : 0;
    }

    private static String moneyText(BigDecimal value) {
        return nullToZero(value).stripTrailingZeros().toPlainString();
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public static BigDecimal money(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(value));
    }

    public static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public static String now() {
        return OffsetDateTime.now().toString();
    }

    public static void stamp(Object model) {
        String now = now();
        try {
            model.getClass().getField("createdAt").set(model, now);
            model.getClass().getField("updatedAt").set(model, now);
        } catch (NoSuchFieldException ignored) {
            try {
                model.getClass().getField("joinedAt").set(model, now);
            } catch (ReflectiveOperationException ignoredAgain) {
                // Some models intentionally have no timestamp fields.
            }
        } catch (ReflectiveOperationException ignored) {
            // Test fixture models are mutable POJOs; reflection keeps the seeding code compact.
        }
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlRowConsumer {
        void accept(ResultSet rs) throws SQLException;
    }

    private record AuthSession(long userId, String expiresAt) {
        boolean expired() {
            try {
                return expiresAt == null || !OffsetDateTime.parse(expiresAt).isAfter(OffsetDateTime.now());
            } catch (RuntimeException ignored) {
                return true;
            }
        }
    }
}
