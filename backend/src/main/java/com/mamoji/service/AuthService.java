package com.mamoji.service;

import com.mamoji.common.Permissions;
import com.mamoji.common.Roles;
import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.finance.domain.Ledger;
import com.mamoji.finance.domain.LedgerMember;
import com.mamoji.platform.identity.User;
import com.mamoji.platform.identity.api.LoginRequest;
import com.mamoji.platform.identity.api.PasswordChangeRequest;
import com.mamoji.platform.identity.api.ProfileUpdateRequest;
import com.mamoji.platform.identity.api.RegistrationInviteCreateRequest;
import com.mamoji.platform.identity.api.RegistrationInviteResponse;
import com.mamoji.platform.identity.api.RegistrationRequest;
import com.mamoji.platform.identity.account.application.LocalUserAccountRepository;
import com.mamoji.platform.identity.invitation.application.IssuedRegistrationInvitation;
import com.mamoji.platform.identity.invitation.application.RegistrationInvitationService;
import com.mamoji.platform.identity.invitation.domain.RegistrationInvitation;
import com.mamoji.platform.identity.security.application.LoginSecurityService;
import com.mamoji.platform.identity.session.application.LocalSessionService;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.service.support.AccessControlService;
import com.mamoji.service.support.PasswordHasher;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.text;
import static com.mamoji.common.PayloadReader.textOr;
import static com.mamoji.service.support.DomainSupport.touch;

@Service
public class AuthService {
    private static final long SESSION_HOURS = 12;
    private static final int SESSION_TOKEN_BYTES = 32;

    private final LocalUserAccountRepository userAccounts;
    private final EnterpriseStore enterpriseStore;
    private final FinanceRepository financeRepository;
    private final AccessControlService accessControl;
    private final PasswordHasher passwordHasher;
    private final LoginSecurityService loginSecurityService;
    private final RegistrationInvitationService invitations;
    private final LocalSessionService sessions;
    private final OutboxEventService outboxEventService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String registrationMode;
    private final int passwordMinLength;
    private final boolean passwordRequireComplexity;

    public AuthService(
        LocalUserAccountRepository userAccounts,
        EnterpriseStore enterpriseStore,
        FinanceRepository financeRepository,
        AccessControlService accessControl,
        PasswordHasher passwordHasher,
        LoginSecurityService loginSecurityService,
        RegistrationInvitationService invitations,
        LocalSessionService sessions,
        OutboxEventService outboxEventService,
        @Value("${mamoji.registration.mode:open}") String registrationMode,
        @Value("${mamoji.security.password.min-length:12}") int passwordMinLength,
        @Value("${mamoji.security.password.require-complexity:false}") boolean passwordRequireComplexity
    ) {
        this.userAccounts = userAccounts;
        this.enterpriseStore = enterpriseStore;
        this.financeRepository = financeRepository;
        this.accessControl = accessControl;
        this.passwordHasher = passwordHasher;
        this.loginSecurityService = loginSecurityService;
        this.invitations = invitations;
        this.sessions = sessions;
        this.outboxEventService = outboxEventService;
        this.registrationMode = textOr(registrationMode, "open").toLowerCase(Locale.ROOT);
        this.passwordMinLength = Math.max(8, passwordMinLength);
        this.passwordRequireComplexity = passwordRequireComplexity;
    }

    public Map<String, Object> login(LoginRequest request, String clientIp) {
        String email = normalizedEmail(request.email());
        String password = request.password();
        loginSecurityService.requireLoginAllowed(email, clientIp);
        Optional<User> matchedUser = userAccounts.findByEmail(email)
            .filter(candidate -> passwordHasher.matches(password, candidate.passwordHash));
        if (matchedUser.isEmpty()) {
            enterpriseStore.auditLog(0, "auth_session", 0, "login_failed", "登录失败: " + maskEmail(email), 0, "anonymous");
            loginSecurityService.recordFailure(email, clientIp)
                .ifPresent(lockedUntil -> enterpriseStore.auditLog(0, "auth_session", 0, "login_locked",
                    "登录失败次数过多，账号或来源临时锁定至: " + lockedUntil, 0, "anonymous"));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        User user = matchedUser.get();
        loginSecurityService.recordSuccess(email);
        if (passwordHasher.needsUpgrade(user.passwordHash)) {
            userAccounts.updatePasswordHashIfCurrent(
                user.id,
                user.passwordHash,
                passwordHasher.hash(password),
                OffsetDateTime.now().toString()
            );
        }
        return authenticated(user);
    }

    @Transactional
    public Map<String, Object> register(RegistrationRequest request) {
        String email = normalizedEmail(request.email());
        String password = request.password();
        if (userAccounts.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }
        RegistrationInvitation invite = invitationForRegistration(email, text(request.inviteToken()));
        validateNewPassword(password);
        User user;
        try {
            user = createUser(
                email,
                textOr(request.nickname(), email.substring(0, email.indexOf("@") > 0 ? email.indexOf("@") : email.length())),
                textOr(request.avatar(), "😊|#3370ff"),
                passwordHasher.hash(password),
                invite == null ? Roles.USER : invite.role(),
                invite == null ? Permissions.ALL : invite.permissions()
            );
        } catch (DuplicateKeyException ignored) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }
        if (invite != null) {
            invite = invitations.accept(invite, user.id, OffsetDateTime.now());
        }
        Ledger ledger = registrationLedger(user.id);
        financeRepository.insertLedger(ledger);
        LedgerMember member = new LedgerMember();
        member.ledgerId = ledger.id;
        member.userId = user.id;
        member.role = "owner";
        member.nickname = user.nickname;
        member.avatar = user.avatar;
        member.joinedAt = OffsetDateTime.now().toString();
        financeRepository.insertLedgerMember(member);
        enterpriseStore.auditLog(0, "user", user.id, "register", "注册用户: " + user.email, user.id, user.nickname);
        outboxEventService.publish("auth.user.registered", 0, "user", user.id, user.id, Map.of(
            "email", user.email,
            "nickname", user.nickname,
            "role", user.role,
            "registrationMode", registrationMode
        ));
        if (invite != null) {
            enterpriseStore.auditLog(0, "registration_invite", invite.id(), "accept", "接受注册邀请: " + user.email, user.id, user.nickname);
            Map<String, Object> invitationEvent = new LinkedHashMap<>();
            invitationEvent.put("email", user.email);
            invitationEvent.put("acceptedUserId", user.id);
            if (invite.invitedByUserId() != null) {
                invitationEvent.put("invitedByUserId", invite.invitedByUserId());
            }
            outboxEventService.publish(
                "auth.registration_invite.accepted",
                0,
                "registration_invite",
                invite.id(),
                user.id,
                invitationEvent
            );
        }
        return authenticated(user);
    }

    private Ledger registrationLedger(long ownerId) {
        Ledger ledger = new Ledger();
        ledger.ownerId = ownerId;
        ledger.name = "公司经营账本";
        ledger.description = "初创公司经营收入、成本、税费与预算";
        ledger.currency = "CNY";
        ledger.isDefault = true;
        ledger.status = 1;
        ledger.createdAt = OffsetDateTime.now().toString();
        ledger.updatedAt = ledger.createdAt;
        return ledger;
    }

    public List<RegistrationInviteResponse> listInvitations(String authorization) {
        accessControl.requireAdmin(authorization);
        return invitations.list().stream().map(RegistrationInviteResponse::summary).toList();
    }

    @Transactional
    public RegistrationInviteResponse createInvitation(
        String authorization,
        RegistrationInviteCreateRequest request
    ) {
        User actor = accessControl.requireAdmin(authorization);
        String email = normalizedEmail(request.email());
        if (userAccounts.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }
        int role = request.role() == null ? Roles.USER : request.role();
        int permissions = (request.permissions() == null ? Permissions.ALL : request.permissions()) & Permissions.ALL;
        int expiresInDays = request.expiresInDays() == null ? 7 : request.expiresInDays();
        OffsetDateTime now = OffsetDateTime.now();
        IssuedRegistrationInvitation issued = invitations.issue(
            email,
            role == Roles.ADMIN ? Roles.ADMIN : Roles.USER,
            permissions == 0 ? Permissions.ALL : permissions,
            now.plusDays(expiresInDays),
            actor.id,
            now
        );
        RegistrationInvitation invite = issued.invitation();
        enterpriseStore.auditLog(0, "registration_invite", invite.id(), "create", "创建注册邀请: " + email, actor.id, actor.nickname);
        outboxEventService.publish("auth.registration_invite.created", 0, "registration_invite", invite.id(), actor.id, Map.of(
            "email", invite.email(),
            "role", invite.role(),
            "permissions", invite.permissions(),
            "expiresAt", invite.expiresAt().toString(),
            "invitedByUserId", actor.id
        ));
        return RegistrationInviteResponse.issued(invite, issued.rawToken());
    }

    public User me(String authorization) {
        return accessControl.requireUser(authorization);
    }

    @Transactional
    public User updateProfile(String authorization, ProfileUpdateRequest request) {
        User current = accessControl.requireUser(authorization);
        User user = userAccounts.findByIdForUpdate(current.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized"));
        if (request.nickname() != null) {
            user.nickname = request.nickname();
        }
        if (request.avatar() != null) {
            user.avatar = request.avatar();
        }
        touch(user);
        userAccounts.update(user);
        enterpriseStore.auditLog(0, "user", user.id, "update_profile", "更新个人资料", user.id, user.nickname);
        return user;
    }

    @Transactional
    public Map<String, Object> changePassword(String authorization, PasswordChangeRequest request) {
        User current = accessControl.requireUser(authorization);
        User user = userAccounts.findByIdForUpdate(current.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized"));
        if (!passwordHasher.matches(request.oldPassword(), user.passwordHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Old password is incorrect");
        }
        String newPassword = request.newPassword();
        validateNewPassword(newPassword);
        user.passwordHash = passwordHasher.hash(newPassword);
        touch(user);
        userAccounts.update(user);
        enterpriseStore.auditLog(0, "user", user.id, "change_password", "修改登录密码", user.id, user.nickname);
        return Map.of("success", true);
    }

    public Map<String, Object> logout(String authorization) {
        sessions.authenticate(authorization)
            .ifPresent(user -> enterpriseStore.auditLog(0, "auth_session", user.id, "logout", "用户退出登录", user.id, user.nickname));
        sessions.revoke(authorization);
        return Map.of("success", true);
    }

    private Map<String, Object> authenticated(User user) {
        String token = sessionToken();
        OffsetDateTime createdAt = OffsetDateTime.now();
        OffsetDateTime expiresAt = createdAt.plusHours(SESSION_HOURS);
        sessions.create(token, user.id, createdAt, expiresAt);
        enterpriseStore.auditLog(0, "auth_session", user.id, "login", "用户登录: " + user.email, user.id, user.nickname);
        return Map.of("token", token, "tokenExpiresAt", expiresAt.toString(), "user", user);
    }

    private String sessionToken() {
        byte[] bytes = new byte[SESSION_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private User createUser(
        String email,
        String nickname,
        String avatar,
        String passwordHash,
        int role,
        int permissions
    ) {
        User user = new User();
        user.email = email;
        user.nickname = nickname;
        user.avatar = avatar == null ? "😊|#3370ff" : avatar;
        user.role = role;
        user.permissions = permissions;
        user.passwordHash = passwordHash;
        String now = OffsetDateTime.now().toString();
        user.createdAt = now;
        user.updatedAt = now;
        return userAccounts.insert(user);
    }

    private RegistrationInvitation invitationForRegistration(String email, String token) {
        if (token.isBlank()) {
            if (inviteRegistrationMode()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Registration invite is required");
            }
            return null;
        }
        return invitations.findUsableForUpdate(token, email, OffsetDateTime.now())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid registration invite"));
    }

    private boolean inviteRegistrationMode() {
        return "invite".equals(registrationMode) || "invitation".equals(registrationMode);
    }

    private String normalizedEmail(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
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
