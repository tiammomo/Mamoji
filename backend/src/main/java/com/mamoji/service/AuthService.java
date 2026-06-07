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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.intValue;
import static com.mamoji.common.PayloadReader.text;
import static com.mamoji.common.PayloadReader.textOr;
import static com.mamoji.service.support.DomainSupport.touch;

@Service
public class AuthService {
    private static final long SESSION_HOURS = 12;

    private final InMemoryStore store;
    private final EnterpriseStore enterpriseStore;
    private final AccessControlService accessControl;
    private final PasswordHasher passwordHasher;
    private final String registrationMode;

    public AuthService(
        InMemoryStore store,
        EnterpriseStore enterpriseStore,
        AccessControlService accessControl,
        PasswordHasher passwordHasher,
        @Value("${mamoji.registration.mode:open}") String registrationMode
    ) {
        this.store = store;
        this.enterpriseStore = enterpriseStore;
        this.accessControl = accessControl;
        this.passwordHasher = passwordHasher;
        this.registrationMode = textOr(registrationMode, "open").toLowerCase(Locale.ROOT);
    }

    public Map<String, Object> login(Map<String, Object> body) {
        String email = text(body.get("email"));
        String password = text(body.get("password"));
        Optional<User> matchedUser = store.findUserByEmail(email)
            .filter(candidate -> passwordHasher.matches(password, candidate.passwordHash));
        if (matchedUser.isEmpty()) {
            enterpriseStore.auditLog(0, "auth_session", 0, "login_failed", "登录失败: " + maskEmail(email), 0, "anonymous");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        User user = matchedUser.get();
        if (passwordHasher.needsUpgrade(user.passwordHash)) {
            user.passwordHash = passwordHasher.hash(password);
            touch(user);
            store.saveUser(user);
        }
        return authenticated(user);
    }

    public Map<String, Object> register(Map<String, Object> body) {
        String email = normalizedEmail(body.get("email"));
        String password = text(body.get("password"));
        if (email.isBlank() || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email and password are required");
        }
        if (!email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid email");
        }
        if (password.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters");
        }
        if (store.findUserByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }
        RegistrationInvite invite = invitationForRegistration(email, text(body.get("inviteToken")));
        User user = store.user(
            email,
            textOr(body.get("nickname"), email.substring(0, email.indexOf("@") > 0 ? email.indexOf("@") : email.length())),
            textOr(body.get("avatar"), "😊|#3370ff"),
            passwordHasher.hash(password),
            invite == null ? Roles.USER : invite.role,
            invite == null ? Permissions.ALL : invite.permissions
        );
        if (invite != null) {
            invite.acceptedAt = OffsetDateTime.now().toString();
            invite.acceptedUserId = user.id;
            touch(invite);
            store.saveRegistrationInvite(invite);
        }
        Ledger ledger = store.ledger(user.id, "公司经营账本", "初创公司经营收入、成本、税费与预算", "CNY", true);
        store.member(ledger.id, user.id, "owner");
        enterpriseStore.auditLog(0, "user", user.id, "register", "注册用户: " + user.email, user.id, user.nickname);
        if (invite != null) {
            enterpriseStore.auditLog(0, "registration_invite", invite.id, "accept", "接受注册邀请: " + user.email, user.id, user.nickname);
        }
        return authenticated(user);
    }

    public List<RegistrationInvite> listInvitations(String authorization) {
        accessControl.requireAdmin(authorization);
        return store.sortedRegistrationInvites();
    }

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
        return invite;
    }

    public User me(String authorization) {
        return accessControl.requireUser(authorization);
    }

    public User updateProfile(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
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

    public Map<String, Object> changePassword(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        if (!passwordHasher.matches(text(body.get("oldPassword")), user.passwordHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Old password is incorrect");
        }
        String newPassword = text(body.get("newPassword"));
        if (newPassword.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 6 characters");
        }
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
        String token = UUID.randomUUID().toString();
        String expiresAt = OffsetDateTime.now().plusHours(SESSION_HOURS).toString();
        store.rememberToken(token, user.id, expiresAt);
        enterpriseStore.auditLog(0, "auth_session", user.id, "login", "用户登录: " + user.email, user.id, user.nickname);
        return Map.of("token", token, "tokenExpiresAt", expiresAt, "user", user);
    }

    private RegistrationInvite invitationForRegistration(String email, String token) {
        if (token.isBlank()) {
            if (inviteRegistrationMode()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Registration invite is required");
            }
            return null;
        }
        RegistrationInvite invite = store.findRegistrationInviteByToken(token)
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
