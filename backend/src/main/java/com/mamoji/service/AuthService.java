package com.mamoji.service;

import com.mamoji.common.Permissions;
import com.mamoji.common.Roles;
import com.mamoji.domain.Models.Ledger;
import com.mamoji.domain.Models.RegistrationInvite;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import com.mamoji.service.support.PasswordHasher;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.intValue;
import static com.mamoji.common.PayloadReader.text;
import static com.mamoji.common.PayloadReader.textOr;
import static com.mamoji.service.support.DomainSupport.touch;

@Service
public class AuthService {
    private static final long SESSION_HOURS = 12;
    private static final int SESSION_TOKEN_BYTES = 32;

    private final InMemoryStore store;
    private final EnterpriseStore enterpriseStore;
    private final AccessControlService accessControl;
    private final PasswordHasher passwordHasher;
    private final LoginSecurityService loginSecurityService;
    private final OutboxEventService outboxEventService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String registrationMode;
    private final int passwordMinLength;
    private final boolean passwordRequireComplexity;

    public AuthService(
        InMemoryStore store,
        EnterpriseStore enterpriseStore,
        AccessControlService accessControl,
        PasswordHasher passwordHasher,
        LoginSecurityService loginSecurityService,
        OutboxEventService outboxEventService,
        @Value("${mamoji.registration.mode:open}") String registrationMode,
        @Value("${mamoji.security.password.min-length:12}") int passwordMinLength,
        @Value("${mamoji.security.password.require-complexity:false}") boolean passwordRequireComplexity
    ) {
        this.store = store;
        this.enterpriseStore = enterpriseStore;
        this.accessControl = accessControl;
        this.passwordHasher = passwordHasher;
        this.loginSecurityService = loginSecurityService;
        this.outboxEventService = outboxEventService;
        this.registrationMode = textOr(registrationMode, "open").toLowerCase(Locale.ROOT);
        this.passwordMinLength = Math.max(8, passwordMinLength);
        this.passwordRequireComplexity = passwordRequireComplexity;
    }

    public Map<String, Object> login(Map<String, Object> body, String clientIp) {
        String email = normalizedEmail(body.get("email"));
        String password = text(body.get("password"));
        loginSecurityService.requireLoginAllowed(email, clientIp);
        Optional<User> matchedUser = store.findUserByEmail(email)
            .filter(candidate -> passwordHasher.matches(password, candidate.passwordHash));
        if (matchedUser.isEmpty()) {
            enterpriseStore.auditLog(0, "auth_session", 0, "login_failed", "登录失败: " + maskEmail(email), 0, "anonymous");
            loginSecurityService.recordFailure(email, clientIp)
                .ifPresent(lockedUntil -> enterpriseStore.auditLog(0, "auth_session", 0, "login_locked",
                    "登录失败次数过多，账号或来源临时锁定至: " + lockedUntil, 0, "anonymous"));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        User user = matchedUser.get();
        loginSecurityService.recordSuccess(email, clientIp);
        if (passwordHasher.needsUpgrade(user.passwordHash)) {
            store.updatePasswordHashIfCurrent(user, passwordHasher.hash(password), InMemoryStore.now());
        }
        return authenticated(user);
    }

    @Transactional
    public Map<String, Object> register(Map<String, Object> body) {
        String email = normalizedEmail(body.get("email"));
        String password = text(body.get("password"));
        if (email.isBlank() || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email and password are required");
        }
        if (!email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid email");
        }
        if (store.findUserByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }
        RegistrationInvite invite = invitationForRegistration(email, text(body.get("inviteToken")));
        validateNewPassword(password);
        User user;
        try {
            user = store.user(
                email,
                textOr(body.get("nickname"), email.substring(0, email.indexOf("@") > 0 ? email.indexOf("@") : email.length())),
                textOr(body.get("avatar"), "😊|#3370ff"),
                passwordHasher.hash(password),
                invite == null ? Roles.USER : invite.role,
                invite == null ? Permissions.ALL : invite.permissions
            );
        } catch (DuplicateKeyException ignored) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }
        if (invite != null) {
            invite.acceptedAt = OffsetDateTime.now().toString();
            invite.acceptedUserId = user.id;
            touch(invite);
            store.saveRegistrationInvite(invite);
        }
        Ledger ledger = store.ledger(user.id, "公司经营账本", "初创公司经营收入、成本、税费与预算", "CNY", true);
        store.member(ledger.id, user.id, "owner");
        enterpriseStore.auditLog(0, "user", user.id, "register", "注册用户: " + user.email, user.id, user.nickname);
        outboxEventService.publish("auth.user.registered", 0, "user", user.id, user.id, Map.of(
            "email", user.email,
            "nickname", user.nickname,
            "role", user.role,
            "registrationMode", registrationMode
        ));
        if (invite != null) {
            enterpriseStore.auditLog(0, "registration_invite", invite.id, "accept", "接受注册邀请: " + user.email, user.id, user.nickname);
            outboxEventService.publish("auth.registration_invite.accepted", 0, "registration_invite", invite.id, user.id, Map.of(
                "email", user.email,
                "acceptedUserId", user.id,
                "invitedByUserId", invite.invitedByUserId
            ));
        }
        return authenticated(user);
    }

    public List<RegistrationInvite> listInvitations(String authorization) {
        accessControl.requireAdmin(authorization);
        return store.sortedRegistrationInvites();
    }

    @Transactional
    public RegistrationInvite createInvitation(String authorization, Map<String, Object> body) {
        User actor = accessControl.requireAdmin(authorization);
        String email = normalizedEmail(body.get("email"));
        if (email.isBlank() || !email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid email is required");
        }
        if (store.findUserByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }
        int role = intValue(body.get("role"), Roles.USER);
        int permissions = intValue(body.get("permissions"), Permissions.ALL) & Permissions.ALL;
        int expiresInDays = Math.min(30, Math.max(1, intValue(body.get("expiresInDays"), 7)));
        RegistrationInvite invite = store.registrationInvite(
            invitationToken(),
            email,
            role == Roles.ADMIN ? Roles.ADMIN : Roles.USER,
            permissions == 0 ? Permissions.ALL : permissions,
            OffsetDateTime.now().plusDays(expiresInDays).toString(),
            actor.id
        );
        enterpriseStore.auditLog(0, "registration_invite", invite.id, "create", "创建注册邀请: " + email, actor.id, actor.nickname);
        outboxEventService.publish("auth.registration_invite.created", 0, "registration_invite", invite.id, actor.id, Map.of(
            "email", invite.email,
            "role", invite.role,
            "permissions", invite.permissions,
            "expiresAt", invite.expiresAt,
            "invitedByUserId", actor.id
        ));
        return invite;
    }

    public User me(String authorization) {
        return accessControl.requireUser(authorization);
    }

    @Transactional
    public User updateProfile(String authorization, Map<String, Object> body) {
        User current = accessControl.requireUser(authorization);
        User user = store.userForUpdate(current.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized"));
        if (body.containsKey("nickname")) {
            user.nickname = text(body.get("nickname"));
        }
        if (body.containsKey("avatar")) {
            user.avatar = text(body.get("avatar"));
        }
        touch(user);
        store.saveUser(user);
        enterpriseStore.auditLog(0, "user", user.id, "update_profile", "更新个人资料", user.id, user.nickname);
        return user;
    }

    @Transactional
    public Map<String, Object> changePassword(String authorization, Map<String, Object> body) {
        User current = accessControl.requireUser(authorization);
        User user = store.userForUpdate(current.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized"));
        if (!passwordHasher.matches(text(body.get("oldPassword")), user.passwordHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Old password is incorrect");
        }
        String newPassword = text(body.get("newPassword"));
        validateNewPassword(newPassword);
        user.passwordHash = passwordHasher.hash(newPassword);
        touch(user);
        store.saveUser(user);
        enterpriseStore.auditLog(0, "user", user.id, "change_password", "修改登录密码", user.id, user.nickname);
        return Map.of("success", true);
    }

    public Map<String, Object> logout(String authorization) {
        store.currentUser(authorization)
            .ifPresent(user -> enterpriseStore.auditLog(0, "auth_session", user.id, "logout", "用户退出登录", user.id, user.nickname));
        store.revokeToken(authorization);
        return Map.of("success", true);
    }

    private Map<String, Object> authenticated(User user) {
        String token = sessionToken();
        String expiresAt = OffsetDateTime.now().plusHours(SESSION_HOURS).toString();
        store.rememberToken(token, user.id, expiresAt);
        enterpriseStore.auditLog(0, "auth_session", user.id, "login", "用户登录: " + user.email, user.id, user.nickname);
        return Map.of("token", token, "tokenExpiresAt", expiresAt, "user", user);
    }

    private String sessionToken() {
        byte[] bytes = new byte[SESSION_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private RegistrationInvite invitationForRegistration(String email, String token) {
        if (token.isBlank()) {
            if (inviteRegistrationMode()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Registration invite is required");
            }
            return null;
        }
        RegistrationInvite invite = store.registrationInviteForUpdate(token)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid registration invite"));
        if (invite.acceptedAt != null && !invite.acceptedAt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Registration invite has already been used");
        }
        if (!invite.email.equalsIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Registration invite email does not match");
        }
        if (OffsetDateTime.parse(invite.expiresAt).isBefore(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Registration invite has expired");
        }
        return invite;
    }

    private boolean inviteRegistrationMode() {
        return "invite".equals(registrationMode) || "invitation".equals(registrationMode);
    }

    private String invitationToken() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private String normalizedEmail(Object value) {
        return text(value).trim().toLowerCase(Locale.ROOT);
    }

    private void validateNewPassword(String password) {
        if (password.length() < passwordMinLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least " + passwordMinLength + " characters");
        }
        if (!passwordRequireComplexity) {
            return;
        }
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
        if (classes < 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Password must contain at least three of lowercase, uppercase, digits and symbols");
        }
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "blank";
        }
        int at = email.indexOf("@");
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
