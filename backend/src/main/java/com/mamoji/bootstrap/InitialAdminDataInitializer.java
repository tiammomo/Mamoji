package com.mamoji.bootstrap;

import com.mamoji.common.Permissions;
import com.mamoji.common.Roles;
import com.mamoji.platform.identity.User;
import com.mamoji.platform.identity.account.application.LocalUserAccountRepository;
import com.mamoji.service.support.PasswordHasher;
import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** Creates the first local administrator when the user-account table is empty. */
@Component
@DependsOn("singleInstanceDatabaseGuard")
public class InitialAdminDataInitializer {
    private final LocalUserAccountRepository userAccounts;
    private final PasswordHasher passwordHasher;
    private final String bootstrapMode;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminNickname;
    private final int passwordMinLength;
    private final boolean passwordRequireComplexity;

    public InitialAdminDataInitializer(
        LocalUserAccountRepository userAccounts,
        PasswordHasher passwordHasher,
        @Value("${mamoji.bootstrap.mode:demo}") String bootstrapMode,
        @Value("${mamoji.bootstrap.admin-email:test@mamoji.com}") String adminEmail,
        @Value("${mamoji.bootstrap.admin-password:123456}") String adminPassword,
        @Value("${mamoji.bootstrap.admin-nickname:Mamoji 公司管理员}") String adminNickname,
        @Value("${mamoji.security.password.min-length:12}") int passwordMinLength,
        @Value("${mamoji.security.password.require-complexity:false}") boolean passwordRequireComplexity
    ) {
        this.userAccounts = userAccounts;
        this.passwordHasher = passwordHasher;
        this.bootstrapMode = defaultIfBlank(bootstrapMode, "demo").toLowerCase(Locale.ROOT);
        this.adminEmail = defaultIfBlank(adminEmail, "test@mamoji.com").trim().toLowerCase(Locale.ROOT);
        this.adminPassword = defaultIfBlank(adminPassword, "123456");
        this.adminNickname = defaultIfBlank(adminNickname, "Mamoji 公司管理员");
        this.passwordMinLength = Math.max(8, passwordMinLength);
        this.passwordRequireComplexity = passwordRequireComplexity;
    }

    @PostConstruct
    void initialize() {
        if (userAccounts.count() != 0) {
            return;
        }
        if ("bootstrap".equals(bootstrapMode)) {
            validateBootstrapAdmin();
        }
        userAccounts.insert(initialAdmin());
    }

    private User initialAdmin() {
        User user = new User();
        user.email = adminEmail;
        user.nickname = adminNickname;
        user.avatar = "😊|#3370ff";
        user.role = Roles.ADMIN;
        user.permissions = Permissions.ALL;
        user.passwordHash = passwordHasher.hash(adminPassword);
        String now = OffsetDateTime.now().toString();
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    private void validateBootstrapAdmin() {
        if (!adminEmail.contains("@")) {
            throw new IllegalStateException("MAMOJI_BOOTSTRAP_ADMIN_EMAIL must be a valid email in bootstrap mode");
        }
        if (adminPassword.length() < passwordMinLength
            || "123456".equals(adminPassword)
            || adminPassword.toLowerCase(Locale.ROOT).contains("replace-with")) {
            throw new IllegalStateException("MAMOJI_BOOTSTRAP_ADMIN_PASSWORD must be replaced with a strong password in bootstrap mode");
        }
        if (passwordRequireComplexity && passwordComplexityClasses(adminPassword) < 3) {
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
}
