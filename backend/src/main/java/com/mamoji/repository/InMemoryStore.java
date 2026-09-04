package com.mamoji.repository;

import com.mamoji.common.Permissions;
import com.mamoji.common.Roles;
import com.mamoji.platform.identity.User;
import com.mamoji.platform.identity.account.application.LocalUserAccountRepository;
import com.mamoji.service.support.PasswordHasher;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

@Component
@DependsOn("singleInstanceDatabaseGuard")
public class InMemoryStore {
    private final LocalUserAccountRepository userAccounts;
    private final PasswordHasher passwordHasher;
    private final String bootstrapMode;
    private final String bootstrapAdminEmail;
    private final String bootstrapAdminPassword;
    private final String bootstrapAdminNickname;
    private final int passwordMinLength;
    private final boolean passwordRequireComplexity;
    public InMemoryStore(
        LocalUserAccountRepository userAccounts,
        PasswordHasher passwordHasher,
        @Value("${mamoji.bootstrap.mode:demo}") String bootstrapMode,
        @Value("${mamoji.bootstrap.admin-email:test@mamoji.com}") String bootstrapAdminEmail,
        @Value("${mamoji.bootstrap.admin-password:123456}") String bootstrapAdminPassword,
        @Value("${mamoji.bootstrap.admin-nickname:Mamoji 公司管理员}") String bootstrapAdminNickname,
        @Value("${mamoji.security.password.min-length:12}") int passwordMinLength,
        @Value("${mamoji.security.password.require-complexity:false}") boolean passwordRequireComplexity
    ) {
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
        if (userAccounts.count() == 0) {
            seedInitialData();
        }
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
        user(
            bootstrapAdminEmail,
            bootstrapAdminNickname,
            "😊|#3370ff",
            passwordHasher.hash(bootstrapAdminPassword),
            Roles.ADMIN,
            Permissions.ALL
        );
    }

    void seedDemoData() {
        user(
            bootstrapAdminEmail,
            bootstrapAdminNickname,
            "😊|#3370ff",
            passwordHasher.hash(bootstrapAdminPassword),
            Roles.ADMIN,
            Permissions.ALL
        );
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
}
