package com.mamoji.repository;

import com.mamoji.common.Permissions;
import com.mamoji.common.Roles;
import com.mamoji.operations.domain.Category;
import com.mamoji.platform.identity.User;
import com.mamoji.platform.identity.account.application.LocalUserAccountRepository;
import com.mamoji.service.support.PasswordHasher;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
    public final Map<Long, Category> categories = new ConcurrentHashMap<>();

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
        categories.clear();

        forEachRow("SELECT * FROM categories", rs -> categories.put(rs.getLong("id"), mapCategory(rs)));
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
        category(testUser.id, "主营业务收入", "💼", "#22c55e", "income");
        category(testUser.id, "团队餐饮", "🍜", "#f97316", "expense");
        category(testUser.id, "差旅交通", "🚇", "#0ea5e9", "expense");
        category(testUser.id, "办公采购", "🛍️", "#a855f7", "expense");
        category(testUser.id, "客户退款", "↩", "#f43f5e", "expense");
        category(testUser.id, "离职补偿", "HR", "#8b5cf6", "expense");
        category(testUser.id, "办公租赁", "🏢", "#6366f1", "expense");
        category(testUser.id, "税费", "🧾", "#ef4444", "expense");

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

    void ensureCompanyAccountingCategories(long ownerId, long companyId) {
        if (queryCategories(ownerId, companyId, "income").isEmpty()) {
            category(ownerId, companyId, "经营收入", "💼", "#22c55e", "income");
        }
        if (queryCategories(ownerId, companyId, "expense").isEmpty()) {
            category(ownerId, companyId, "经营支出", "🧾", "#ef4444", "expense");
        }
    }

    /** Transitional cache hook for legacy readers after a committed category write. */
    public void synchronizeCategoryAfterCommit(Category category) {
        afterCommit(() -> categories.put(category.id, category));
    }

    /** Transitional cache hook for legacy readers after a committed category deletion. */
    public void removeCategoryFromCompatibilityViewAfterCommit(long id) {
        afterCommit(() -> categories.remove(id));
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

    /**
     * Completes the V5 compatibility backfill once enterprise subjects and
     * employee access records have been initialized. SQL migration V5 can only
     * infer companies owned directly by a user; this pass also covers users who
     * access their default company through an employee record.
     */
    public void assignUnscopedCategoryData(Map<Long, Long> defaultCompanyByUser) {
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
