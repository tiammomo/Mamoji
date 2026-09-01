package com.mamoji.repository;

import com.mamoji.common.Permissions;
import com.mamoji.common.Roles;
import com.mamoji.finance.domain.Account;
import com.mamoji.finance.domain.Ledger;
import com.mamoji.finance.domain.LedgerMember;
import com.mamoji.operations.domain.Category;
import com.mamoji.platform.identity.User;
import com.mamoji.platform.identity.account.application.LocalUserAccountRepository;
import com.mamoji.service.support.PasswordHasher;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@DependsOn("singleInstanceDatabaseGuard")
public class InMemoryStore {
    public final Map<Long, Account> accounts = new ConcurrentHashMap<>();
    public final Map<Long, Category> categories = new ConcurrentHashMap<>();
    public final Map<Long, Ledger> ledgers = new ConcurrentHashMap<>();
    public final Map<Long, LedgerMember> ledgerMembers = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;
    private final LocalUserAccountRepository userAccounts;
    private final PasswordHasher passwordHasher;
    private final String bootstrapMode;
    private final String bootstrapAdminEmail;
    private final String bootstrapAdminPassword;
    private final String bootstrapAdminNickname;
    private final int passwordMinLength;
    private final boolean passwordRequireComplexity;
    public InMemoryStore(
        JdbcTemplate jdbc,
        LocalUserAccountRepository userAccounts,
        PasswordHasher passwordHasher,
        @Value("${mamoji.bootstrap.mode:demo}") String bootstrapMode,
        @Value("${mamoji.bootstrap.admin-email:test@mamoji.com}") String bootstrapAdminEmail,
        @Value("${mamoji.bootstrap.admin-password:123456}") String bootstrapAdminPassword,
        @Value("${mamoji.bootstrap.admin-nickname:Mamoji 公司管理员}") String bootstrapAdminNickname,
        @Value("${mamoji.security.password.min-length:12}") int passwordMinLength,
        @Value("${mamoji.security.password.require-complexity:false}") boolean passwordRequireComplexity
    ) {
        this.jdbc = jdbc;
        this.userAccounts = userAccounts;
        this.passwordHasher = passwordHasher;
        this.bootstrapMode = defaultIfBlank(bootstrapMode, "demo").toLowerCase(Locale.ROOT);
        this.bootstrapAdminEmail = defaultIfBlank(bootstrapAdminEmail, "test@mamoji.com")
            .trim()
            .toLowerCase(Locale.ROOT);
        this.bootstrapAdminPassword = defaultIfBlank(bootstrapAdminPassword, "123456");
        this.bootstrapAdminNickname = defaultIfBlank(bootstrapAdminNickname, "Mamoji 公司管理员");
        this.passwordMinLength = Math.max(8, passwordMinLength);
        this.passwordRequireComplexity = passwordRequireComplexity;
    }

    @PostConstruct
    void initialize() {
        loadAll();
        if (userAccounts.count() == 0) {
            seedInitialData();
        }
    }

    private void loadAll() {
        accounts.clear();
        categories.clear();
        ledgers.clear();
        ledgerMembers.clear();

        forEachRow("SELECT * FROM accounts", rs -> accounts.put(rs.getLong("id"), mapAccount(rs)));
        forEachRow("SELECT * FROM categories", rs -> categories.put(rs.getLong("id"), mapCategory(rs)));
        forEachRow("SELECT * FROM ledgers", rs -> ledgers.put(rs.getLong("id"), mapLedger(rs)));
        forEachRow("SELECT * FROM ledger_members", rs -> ledgerMembers.put(rs.getLong("id"), mapLedgerMember(rs)));

    }

    /** Reload the process-local compatibility view after a controlled restore. */
    public synchronized void reloadFromDatabase() {
        loadAll();
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

        category(testUser.id, "主营业务收入", "💼", "#22c55e", "income");
        category(testUser.id, "团队餐饮", "🍜", "#f97316", "expense");
        category(testUser.id, "差旅交通", "🚇", "#0ea5e9", "expense");
        category(testUser.id, "办公采购", "🛍️", "#a855f7", "expense");
        category(testUser.id, "客户退款", "↩", "#f43f5e", "expense");
        category(testUser.id, "离职补偿", "HR", "#8b5cf6", "expense");
        category(testUser.id, "办公租赁", "🏢", "#6366f1", "expense");
        category(testUser.id, "税费", "🧾", "#ef4444", "expense");

        account(testUser.id, defaultLedger.id, "公司现金备用金", "cash", "备用金", null, "1200");
        account(testUser.id, defaultLedger.id, "公司基本户", "bank", "对公账户", "招商银行", "26300");
        account(testUser.id, defaultLedger.id, "企业信用卡", "credit", "信用卡", "招商银行", "1800");
    }

    private void validateBootstrapAdmin() {
        if (bootstrapAdminEmail == null || bootstrapAdminEmail.isBlank() || !bootstrapAdminEmail.contains("@")) {
            throw new IllegalStateException("MAMOJI_BOOTSTRAP_ADMIN_EMAIL must be a valid email in bootstrap mode");
        }
        if (bootstrapAdminPassword == null
            || bootstrapAdminPassword.length() < passwordMinLength
            || "123456".equals(bootstrapAdminPassword)
            || bootstrapAdminPassword.toLowerCase(Locale.ROOT).contains("replace-with")) {
            throw new IllegalStateException("MAMOJI_BOOTSTRAP_ADMIN_PASSWORD must be replaced with a strong password in bootstrap mode");
        }
        if (passwordRequireComplexity && passwordComplexityClasses(bootstrapAdminPassword) < 3) {
            throw new IllegalStateException("MAMOJI_BOOTSTRAP_ADMIN_PASSWORD must contain at least three of lowercase, uppercase, digits and symbols in bootstrap mode");
        }
    }

    private int passwordComplexityClasses(String password) {
        int classes = 0;
        if (password.chars().anyMatch(Character::isLowerCase)) {
            classes++;
        }
        if (password.chars().anyMatch(Character::isUpperCase)) {
            classes++;
        }
        if (password.chars().anyMatch(Character::isDigit)) {
            classes++;
        }
        if (password.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch))) {
            classes++;
        }
        return classes;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private User user(String email, String nickname, String avatar, String password, int role, int permissions) {
        User user = new User();
        user.email = email;
        user.nickname = nickname;
        user.avatar = avatar == null ? "😊|#3370ff" : avatar;
        user.role = role;
        user.permissions = permissions;
        user.passwordHash = password;
        stamp(user);
        return userAccounts.insert(user);
    }

    private Account account(long userId, Long ledgerId, String name, String type, String subType, String bank, String balance) {
        Long companyId = ledgerId == null ? null : findLedger(ledgerId).map(ledger -> ledger.companyId).orElse(null);
        return account(userId, companyId, ledgerId, name, type, subType, bank, balance);
    }

    private Account account(long userId, Long companyId, Long ledgerId, String name, String type, String subType, String bank, String balance) {
        Account account = new Account();
        account.userId = userId;
        account.companyId = companyId;
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
                last_reconciled_at, owner_name, purpose, reconciliation_status, risk_level, created_at, updated_at, company_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindAccountInsert(ps, account));
        afterCommit(() -> accounts.put(account.id, account));
        return account;
    }

    private Category category(long userId, String name, String icon, String color, String type) {
        return category(userId, null, name, icon, color, type);
    }

    private Category category(long userId, Long companyId, String name, String icon, String color, String type) {
        Category category = new Category();
        category.userId = userId;
        category.companyId = companyId;
        category.name = name;
        category.icon = icon;
        category.color = color;
        category.type = type;
        category.status = 1;
        stamp(category);
        category.id = insert("""
            INSERT INTO categories (name, icon, color, type, user_id, status, created_at, updated_at, company_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindCategoryInsert(ps, category));
        afterCommit(() -> categories.put(category.id, category));
        return category;
    }

    private Ledger ledger(long ownerId, String name, String description, String currency, boolean isDefault) {
        return ledger(ownerId, null, name, description, currency, isDefault);
    }

    private Ledger ledger(long ownerId, Long companyId, String name, String description, String currency, boolean isDefault) {
        Ledger ledger = new Ledger();
        ledger.ownerId = ownerId;
        ledger.companyId = companyId;
        ledger.name = name;
        ledger.description = description == null ? "" : description;
        ledger.currency = currency == null ? "CNY" : currency;
        ledger.isDefault = isDefault;
        ledger.status = 1;
        stamp(ledger);
        ledger.id = insert("""
            INSERT INTO ledgers (name, description, currency, owner_id, is_default, status, created_at, updated_at, company_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> bindLedgerInsert(ps, ledger));
        afterCommit(() -> ledgers.put(ledger.id, ledger));
        return ledger;
    }

    private LedgerMember member(long ledgerId, long userId, String role) {
        LedgerMember member = new LedgerMember();
        member.ledgerId = ledgerId;
        member.userId = userId;
        member.role = role;
        User memberUser = userAccounts.findById(userId).orElse(null);
        if (memberUser != null) {
            member.nickname = memberUser.nickname;
            member.avatar = memberUser.avatar;
        }
        member.joinedAt = now();
        member.id = insert("""
            INSERT INTO ledger_members (ledger_id, user_id, role, nickname, avatar, joined_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, ps -> bindLedgerMemberInsert(ps, member));
        afterCommit(() -> ledgerMembers.put(member.id, member));
        return member;
    }

    Ledger ensureCompanyAccountingWorkspace(long ownerId, long companyId, String currency, String subjectName) {
        Ledger ledger = queryLedgers(ownerId, companyId).stream()
            .filter(candidate -> candidate.isDefault)
            .min(Comparator.comparing(candidate -> candidate.id))
            .orElseGet(() -> ledger(
                ownerId,
                companyId,
                textOr(subjectName, "经营主体") + "账本",
                "主体默认经营账本",
                textOr(currency, "CNY"),
                true
            ));
        if (!ledgerMemberExists(ledger.id, ownerId)) {
            member(ledger.id, ownerId, "owner");
        }
        ensureCompanyAccountingCategories(ownerId, companyId);
        return ledger;
    }

    private void ensureCompanyAccountingCategories(long ownerId, long companyId) {
        if (queryCategories(ownerId, companyId, "income").isEmpty()) {
            category(ownerId, companyId, "经营收入", "💼", "#22c55e", "income");
        }
        if (queryCategories(ownerId, companyId, "expense").isEmpty()) {
            category(ownerId, companyId, "经营支出", "🧾", "#ef4444", "expense");
        }
    }

    /** Transitional cache hook for legacy readers after a committed account write. */
    public void synchronizeAccountAfterCommit(Account account) {
        afterCommit(() -> accounts.put(account.id, account));
    }

    /** Transitional cache hook for legacy readers after a committed account deletion. */
    public void removeAccountFromCompatibilityViewAfterCommit(long id) {
        afterCommit(() -> accounts.remove(id));
    }

    /** Transitional cache hook for legacy readers after a committed category write. */
    public void synchronizeCategoryAfterCommit(Category category) {
        afterCommit(() -> categories.put(category.id, category));
    }

    /** Transitional cache hook for legacy readers after a committed category deletion. */
    public void removeCategoryFromCompatibilityViewAfterCommit(long id) {
        afterCommit(() -> categories.remove(id));
    }

    /** Transitional cache hook for legacy readers after a committed ledger creation. */
    public void synchronizeLedgerAfterCommit(Ledger ledger) {
        afterCommit(() -> ledgers.put(ledger.id, ledger));
    }

    /** Transitional cache hook for legacy readers after a committed ledger-member creation. */
    public void synchronizeLedgerMemberAfterCommit(LedgerMember member) {
        afterCommit(() -> ledgerMembers.put(member.id, member));
    }

    /** Transitional cache hook for legacy readers after a committed ledger-member deletion. */
    public void removeLedgerMemberFromCompatibilityViewAfterCommit(long ledgerId, long userId) {
        afterCommit(() -> ledgerMembers.values().removeIf(
            member -> member.ledgerId == ledgerId && member.userId == userId
        ));
    }

    private Optional<Account> findAccount(long id) {
        return jdbc.query("SELECT * FROM accounts WHERE id = ?", (rs, rowNum) -> mapAccount(rs), id).stream().findFirst();
    }

    private Optional<Category> findCategory(long id) {
        return jdbc.query("SELECT * FROM categories WHERE id = ?", (rs, rowNum) -> mapCategory(rs), id).stream().findFirst();
    }

    private List<Category> queryCategories(long userId, long companyId, String type) {
        if (type == null || type.isBlank()) {
            return jdbc.query(
                "SELECT * FROM categories WHERE user_id = ? AND company_id = ? ORDER BY id",
                (rs, rowNum) -> mapCategory(rs), userId, companyId
            );
        }
        return jdbc.query(
            "SELECT * FROM categories WHERE user_id = ? AND company_id = ? AND type = ? ORDER BY id",
            (rs, rowNum) -> mapCategory(rs), userId, companyId, type
        );
    }

    private Optional<Ledger> findLedger(long id) {
        return jdbc.query("SELECT * FROM ledgers WHERE id = ?", (rs, rowNum) -> mapLedger(rs), id).stream().findFirst();
    }

    private List<Ledger> queryLedgers(long ownerId, long companyId) {
        return jdbc.query(
            "SELECT * FROM ledgers WHERE owner_id = ? AND company_id = ? ORDER BY is_default DESC, id",
            (rs, rowNum) -> mapLedger(rs), ownerId, companyId
        );
    }

    private boolean ledgerMemberExists(long ledgerId, long userId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ledger_members WHERE ledger_id = ? AND user_id = ?",
            Integer.class,
            ledgerId,
            userId
        );
        return count != null && count > 0;
    }

    private List<Account> sortedAccounts() {
        return jdbc.query("SELECT * FROM accounts ORDER BY id", (rs, rowNum) -> mapAccount(rs));
    }

    private List<Category> sortedCategories() {
        return jdbc.query("SELECT * FROM categories ORDER BY id", (rs, rowNum) -> mapCategory(rs));
    }

    /**
     * Completes the V5 compatibility backfill once enterprise subjects and
     * employee access records have been initialized. SQL migration V5 can only
     * infer companies owned directly by a user; this pass also covers users who
     * access their default company through an employee record.
     */
    public void assignUnscopedAccountingData(Map<Long, Long> defaultCompanyByUser) {
        ledgers.values().stream().filter(ledger -> ledger.companyId == null).forEach(ledger -> {
            ledger.companyId = defaultCompanyByUser.get(ledger.ownerId);
            if (ledger.companyId != null) {
                jdbc.update("UPDATE ledgers SET company_id = ? WHERE id = ? AND company_id IS NULL", ledger.companyId, ledger.id);
            }
        });
        accounts.values().stream().filter(account -> account.companyId == null).forEach(account -> {
            account.companyId = Optional.ofNullable(account.ledgerId)
                .map(ledgers::get)
                .map(ledger -> ledger.companyId)
                .orElse(defaultCompanyByUser.get(account.userId));
            if (account.companyId != null) {
                jdbc.update("UPDATE accounts SET company_id = ? WHERE id = ? AND company_id IS NULL", account.companyId, account.id);
            }
        });
        categories.values().stream().filter(category -> category.companyId == null).forEach(category -> {
            category.companyId = defaultCompanyByUser.get(category.userId);
            if (category.companyId != null) {
                jdbc.update("UPDATE categories SET company_id = ? WHERE id = ? AND company_id IS NULL", category.companyId, category.id);
            }
        });
    }

    public void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private Account mapAccount(ResultSet rs) throws SQLException {
        Account account = new Account();
        account.id = rs.getLong("id");
        account.version = rs.getLong("version");
        account.companyId = nullableLong(rs, "company_id");
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
        category.companyId = nullableLong(rs, "company_id");
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

    private Ledger mapLedger(ResultSet rs) throws SQLException {
        Ledger ledger = new Ledger();
        ledger.id = rs.getLong("id");
        ledger.companyId = nullableLong(rs, "company_id");
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
        setLongOrNull(ps, 24, account.companyId);
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
        setLongOrNull(ps, 9, category.companyId);
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
        setLongOrNull(ps, 9, ledger.companyId);
    }

    private void bindLedgerMemberInsert(PreparedStatement ps, LedgerMember member) throws SQLException {
        ps.setLong(1, member.ledgerId);
        ps.setLong(2, member.userId);
        ps.setString(3, member.role);
        ps.setString(4, member.nickname);
        ps.setString(5, member.avatar);
        ps.setString(6, member.joinedAt);
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

}
