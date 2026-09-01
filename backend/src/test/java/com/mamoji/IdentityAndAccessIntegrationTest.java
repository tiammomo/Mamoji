package com.mamoji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.platform.audit.application.AuditLogRepository;
import com.mamoji.platform.audit.domain.AuditEvent;
import com.mamoji.platform.identity.User;
import com.mamoji.platform.identity.account.application.LocalUserAccountRepository;
import com.mamoji.platform.identity.account.application.UserDirectory;
import com.mamoji.platform.identity.invitation.domain.InvitationTokenDigest;
import com.mamoji.platform.identity.security.application.LoginFailureRepository;
import com.mamoji.platform.identity.security.application.LoginSecurityService;
import com.mamoji.platform.identity.security.domain.LoginThrottleSubject;
import com.mamoji.platform.identity.session.application.LocalSessionRepository;
import com.mamoji.platform.identity.session.application.LocalSessionService;
import com.mamoji.platform.identity.session.domain.LocalSession;
import com.mamoji.platform.identity.session.domain.SessionTokenDigest;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.BackupService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class IdentityAndAccessIntegrationTest extends AbstractPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    LoginFailureRepository loginFailureRepository;

    @Autowired
    LoginSecurityService loginSecurityService;

    @Autowired
    LocalSessionRepository localSessionRepository;

    @Autowired
    LocalSessionService localSessionService;

    @Autowired
    BackupService backupService;

    @Autowired
    LocalUserAccountRepository userAccounts;

    @Autowired
    UserDirectory userDirectory;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void loginHidesPasswordHashAndLogoutInvalidatesToken() throws Exception {
        Map<String, Object> session = login("test@mamoji.com", "123456");
        String token = text(session.get("token"));

        assertTrue(token.length() >= 40);
        assertFalse(toJson(session).contains("passwordHash"));
        SessionTokenDigest tokenDigest = SessionTokenDigest.fromRawToken(token);
        assertEquals(tokenDigest.value(), jdbc.queryForObject(
            "SELECT token FROM auth_tokens WHERE token = ?",
            String.class,
            tokenDigest.value()
        ));
        assertFalse(tokenDigest.value().contains(token));

        LocalSessionService restartedSessions = new LocalSessionService(localSessionRepository);
        assertTrue(restartedSessions.authenticate("Bearer " + token).isPresent());

        ApiResponse me = request("GET", "/api/v1/auth/me", null, token);
        assertEquals(200, me.status());
        assertFalse(me.body().contains("passwordHash"));

        ApiResponse logout = request("POST", "/api/v1/auth/logout", null, token);
        assertEquals(200, logout.status());

        ApiResponse meAfterLogout = request("GET", "/api/v1/auth/me", null, token);
        assertEquals(401, meAfterLogout.status());
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM auth_tokens WHERE token = ?",
            Integer.class,
            tokenDigest.value()
        ));
    }

    @Test
    void sessionCleanupRemovesExpiredRowsAndDatabaseRejectsPlaintextTokens() {
        long administratorId = jdbc.queryForObject(
            "SELECT id FROM users WHERE email = 'test@mamoji.com'",
            Long.class
        );
        OffsetDateTime now = OffsetDateTime.now();
        LocalSession expired = new LocalSession(
            SessionTokenDigest.fromRawToken("e".repeat(43)),
            administratorId,
            now.minusHours(2),
            now.minusHours(1)
        );
        localSessionRepository.insert(expired);

        localSessionService.purgeExpiredSessions();

        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM auth_tokens WHERE token = ?",
            Integer.class,
            expired.tokenDigest().value()
        ));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            INSERT INTO auth_tokens (token, user_id, created_at, expires_at)
            VALUES ('plaintext-token', ?, ?, ?)
            """, administratorId, now, now.plusHours(1)));
    }

    @Test
    void invitationCredentialIsDisclosedOnceAndConcurrentRegistrationConsumesItOnce() throws Exception {
        String email = uniqueEmail("single-use-invitation");
        String administratorToken = adminToken();
        ApiResponse createdResponse = request("POST", "/api/v1/auth/invitations", Map.of(
            "email", email,
            "role", 2,
            "permissions", 1,
            "expiresInDays", 1
        ), administratorToken);
        assertEquals(200, createdResponse.status(), createdResponse.body());
        Map<String, Object> created = parseMap(createdResponse.body());
        String rawToken = text(created.get("token"));
        long invitationId = ((Number) created.get("id")).longValue();
        InvitationTokenDigest digest = InvitationTokenDigest.fromRawToken(rawToken);

        assertEquals(64, rawToken.length());
        assertEquals(digest.value(), jdbc.queryForObject(
            "SELECT token FROM registration_invites WHERE id = ?",
            String.class,
            invitationId
        ));
        assertFalse(digest.value().contains(rawToken));

        ApiResponse listedResponse = request("GET", "/api/v1/auth/invitations", null, administratorToken);
        assertEquals(200, listedResponse.status(), listedResponse.body());
        assertFalse(listedResponse.body().contains(rawToken));
        assertFalse(listedResponse.body().contains(digest.value()));
        Map<String, Object> listed = parseList(listedResponse.body()).stream()
            .filter(row -> ((Number) row.get("id")).longValue() == invitationId)
            .findFirst()
            .orElseThrow();
        assertTrue(listed.containsKey("token"));
        assertNull(listed.get("token"));

        Map<String, Object> registration = Map.of(
            "email", email,
            "nickname", "Concurrent invite member",
            "password", "Member-Password-123!",
            "inviteToken", rawToken
        );
        CompletableFuture<ApiResponse> first = requestAsync(
            "POST", "/api/v1/auth/register", registration, null
        );
        CompletableFuture<ApiResponse> second = requestAsync(
            "POST", "/api/v1/auth/register", registration, null
        );

        ApiResponse firstResponse = first.get(10, TimeUnit.SECONDS);
        ApiResponse secondResponse = second.get(10, TimeUnit.SECONDS);
        List<Integer> statuses = List.of(firstResponse.status(), secondResponse.status());

        assertEquals(1, statuses.stream().filter(status -> status == 200).count(),
            firstResponse.body() + " / " + secondResponse.body());
        assertTrue(statuses.stream().anyMatch(status -> status == 403 || status == 409),
            firstResponse.body() + " / " + secondResponse.body());
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email
        ));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM registration_invites invitation
            JOIN users accepted_user ON accepted_user.id = invitation.accepted_user_id
            WHERE invitation.id = ? AND invitation.accepted_at IS NOT NULL AND accepted_user.email = ?
            """, Integer.class, invitationId, email));
    }

    @Test
    @Transactional
    void structuredRestoreUpgradesLegacyRawInvitationTokensAndTypedTimestamps() throws Exception {
        String email = uniqueEmail("legacy-backup-invitation");
        String administratorToken = adminToken();
        ApiResponse createdResponse = request("POST", "/api/v1/auth/invitations", Map.of(
            "email", email,
            "role", 2,
            "permissions", 1,
            "expiresInDays", 1
        ), administratorToken);
        assertEquals(200, createdResponse.status(), createdResponse.body());
        Map<String, Object> created = parseMap(createdResponse.body());
        long invitationId = ((Number) created.get("id")).longValue();
        String rawToken = text(created.get("token"));
        String digest = InvitationTokenDigest.fromRawToken(rawToken).value();

        ResponseEntity<Map<String, Object>> exported = backupService.export("Bearer " + administratorToken);
        Map<String, Object> payload = exported.getBody();
        assertNotNull(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        assertFalse(((List<?>) data.get("company_memberships")).isEmpty());
        assertTrue(data.get("budget_reservations") instanceof List<?>);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> invitationRows = (List<Map<String, Object>>) data.get("registration_invites");
        Map<String, Object> exportedInvitation = invitationRows.stream()
            .filter(row -> Long.parseLong(String.valueOf(row.get("id"))) == invitationId)
            .findFirst()
            .orElseThrow();
        assertEquals(digest, exportedInvitation.get("token"));

        exportedInvitation.put("token", rawToken);
        data.remove("company_memberships");
        data.remove("budget_reservations");
        payload.put("version", "2.0");
        ObjectMapper backupMapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_LONG_FOR_INTS);
        Map<String, Object> normalizedPayload = backupMapper.readValue(
            backupMapper.writeValueAsBytes(payload),
            new TypeReference<>() {}
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> normalizedData = (Map<String, Object>) normalizedPayload.get("data");
        normalizedPayload.put("checksum", sha256(backupMapper.writeValueAsBytes(normalizedData)));
        MockMultipartFile legacyBackup = new MockMultipartFile(
            "file",
            "legacy-invitation-backup.json",
            "application/json",
            backupMapper.writeValueAsBytes(normalizedPayload)
        );

        Map<String, Object> restored = backupService.restore(
            "Bearer " + administratorToken,
            legacyBackup,
            "RESTORE",
            false
        );

        assertEquals(true, restored.get("restored"));
        assertEquals("2.0", restored.get("sourceVersion"));
        assertEquals("2.1", restored.get("targetVersion"));
        assertEquals(digest, jdbc.queryForObject(
            "SELECT token FROM registration_invites WHERE id = ?",
            String.class,
            invitationId
        ));
        assertNotNull(jdbc.queryForObject(
            "SELECT expires_at FROM registration_invites WHERE id = ?",
            OffsetDateTime.class,
            invitationId
        ));
        assertTrue(jdbc.queryForObject(
            "SELECT COUNT(*) FROM company_memberships",
            Integer.class
        ) > 0);
    }

    @Test
    @Transactional
    void currentStructuredBackupCoversEveryTableAndRoundTripsMemberships() throws Exception {
        String administratorToken = adminToken();
        int membershipsBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM company_memberships",
            Integer.class
        );

        ResponseEntity<Map<String, Object>> exported = backupService.export("Bearer " + administratorToken);
        Map<String, Object> payload = exported.getBody();
        assertNotNull(payload);
        assertEquals("2.1", payload.get("version"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        Set<String> coveredTables = new java.util.HashSet<>(data.keySet());
        coveredTables.addAll(Set.of(
            "auth_tokens",
            "login_failure_states",
            "notification_deliveries",
            "outbox_events"
        ));
        assertEquals(Set.copyOf(jdbc.queryForList("""
            SELECT tablename
            FROM pg_tables
            WHERE schemaname = current_schema() AND tablename <> 'flyway_schema_history'
            """, String.class)), coveredTables);

        MockMultipartFile currentBackup = new MockMultipartFile(
            "file",
            "current-structured-backup.json",
            "application/json",
            toJson(payload).getBytes(StandardCharsets.UTF_8)
        );
        Map<String, Object> restored = backupService.restore(
            "Bearer " + administratorToken,
            currentBackup,
            "RESTORE",
            false
        );

        assertEquals("2.1", restored.get("sourceVersion"));
        assertEquals(membershipsBefore, jdbc.queryForObject(
            "SELECT COUNT(*) FROM company_memberships",
            Integer.class
        ));
    }

    @Test
    void failedLoginLockSurvivesServiceInstancesAndReturnsTooManyRequests() throws Exception {
        String email = uniqueEmail("persistent-login-lock");
        String key = LoginThrottleSubject.email(email).keyHash();

        for (int attempt = 1; attempt <= 5; attempt++) {
            ApiResponse response = request("POST", "/api/v1/auth/login", Map.of(
                "email", email,
                "password", "Wrong-Password-123!"
            ), null);
            assertEquals(401, response.status(), response.body());
        }

        assertEquals(5, jdbc.queryForObject(
            "SELECT failed_attempts FROM login_failure_states WHERE subject_key = ?",
            Integer.class,
            key
        ));
        assertNotNull(jdbc.queryForObject(
            "SELECT locked_until FROM login_failure_states WHERE subject_key = ?",
            java.time.OffsetDateTime.class,
            key
        ));

        LoginSecurityService restartedService = new LoginSecurityService(
            loginFailureRepository, 5, 50, 15, 15
        );
        ResponseStatusException locked = assertThrows(
            ResponseStatusException.class,
            () -> restartedService.requireLoginAllowed(email, "203.0.113.90")
        );
        assertEquals(429, locked.getStatusCode().value());

        ApiResponse blocked = request("POST", "/api/v1/auth/login", Map.of(
            "email", email,
            "password", "Wrong-Password-123!"
        ), null);
        assertEquals(429, blocked.status(), blocked.body());
    }

    @Test
    void concurrentFailuresIncrementAtomicallyAndSuccessClearsOnlyTheAccountWindow() throws Exception {
        String email = uniqueEmail("concurrent-login-lock");
        String emailKey = LoginThrottleSubject.email(email).keyHash();
        List<CompletableFuture<Void>> attempts = new java.util.ArrayList<>();
        List<String> sources = new java.util.ArrayList<>();
        for (int attempt = 1; attempt <= 5; attempt++) {
            String source = "203.0.113." + attempt;
            sources.add(source);
            attempts.add(CompletableFuture.runAsync(() ->
                loginSecurityService.recordFailure(email, source)
            ));
        }

        CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);

        assertEquals(5, jdbc.queryForObject(
            "SELECT failed_attempts FROM login_failure_states WHERE subject_key = ?",
            Integer.class,
            emailKey
        ));
        assertNotNull(jdbc.queryForObject(
            "SELECT locked_until FROM login_failure_states WHERE subject_key = ?",
            java.time.OffsetDateTime.class,
            emailKey
        ));

        loginSecurityService.recordSuccess(email);

        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM login_failure_states WHERE subject_key = ?",
            Integer.class,
            emailKey
        ));
        for (String source : sources) {
            assertEquals(1, jdbc.queryForObject(
                "SELECT failed_attempts FROM login_failure_states WHERE subject_key = ?",
                Integer.class,
                LoginThrottleSubject.source(source).keyHash()
            ));
        }
    }

    @Test
    void authCommandValidationRejectsInvalidPayloadsBeforeWriting() throws Exception {
        int usersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        ApiResponse invalidRegistration = request("POST", "/api/v1/auth/register", Map.of(
            "email", "not-an-email",
            "password", "",
            "nickname", "x".repeat(101)
        ), null);

        assertValidationFields(invalidRegistration, Set.of("email", "password", "nickname"));
        assertEquals(usersBefore, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));

        ApiResponse invalidLogin = request("POST", "/api/v1/auth/login", Map.of(
            "email", "test@mamoji.com"
        ), null);
        assertValidationFields(invalidLogin, Set.of("password"));

        String adminToken = text(login("test@mamoji.com", "123456").get("token"));
        int invitationsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM registration_invites", Integer.class);
        ApiResponse invalidInvitation = request("POST", "/api/v1/auth/invitations", Map.of(
            "email", "invalid",
            "role", 3,
            "permissions", com.mamoji.common.Permissions.ALL + 1,
            "expiresInDays", 31
        ), adminToken);
        assertValidationFields(invalidInvitation, Set.of("email", "role", "permissions", "expiresInDays"));
        assertEquals(invitationsBefore, jdbc.queryForObject(
            "SELECT COUNT(*) FROM registration_invites", Integer.class
        ));

        String nicknameBefore = jdbc.queryForObject(
            "SELECT nickname FROM users WHERE email = 'test@mamoji.com'", String.class
        );
        ApiResponse invalidProfile = request("PUT", "/api/v1/auth/profile", Map.of(
            "nickname", "   "
        ), adminToken);
        assertValidationFields(invalidProfile, Set.of("nickname"));
        assertEquals(nicknameBefore, jdbc.queryForObject(
            "SELECT nickname FROM users WHERE email = 'test@mamoji.com'", String.class
        ));
    }

    @Test
    void inviteModeBlocksPublicRegistrationAndAllowsInvitedRegistration() throws Exception {
        String email = uniqueEmail("invited");
        String password = "Member-Password-123!";

        ApiResponse blocked = request("POST", "/api/v1/auth/register", Map.of(
            "email", email,
            "nickname", "Blocked Member",
            "password", password
        ), null);
        assertEquals(403, blocked.status());

        String inviteToken = createInvite(email, Permissions.USER);
        ApiResponse registered = request("POST", "/api/v1/auth/register", Map.of(
            "email", email,
            "nickname", "Invited Member",
            "avatar", "🧭|#123456",
            "password", password,
            "inviteToken", inviteToken
        ), null);
        assertEquals(200, registered.status());
        Map<String, Object> session = parseMap(registered.body());
        assertNotNull(session.get("token"));
        assertFalse(registered.body().contains("passwordHash"));
        Map<String, Object> membership = jdbc.queryForMap("""
            SELECT lm.nickname, lm.avatar
            FROM ledger_members lm
            JOIN users u ON u.id = lm.user_id
            WHERE u.email = ?
            """, email);
        assertEquals("Invited Member", membership.get("nickname"));
        assertEquals("🧭|#123456", membership.get("avatar"));
    }

    private void assertValidationFields(ApiResponse response, Set<String> expectedFields) throws Exception {
        assertEquals(400, response.status(), response.body());
        Map<String, Object> problem = parseMap(response.body());
        assertEquals("validation_failed", problem.get("code"));
        assertTrue(problem.get("fields") instanceof Map<?, ?>, response.body());
        Map<?, ?> fields = (Map<?, ?>) problem.get("fields");
        assertTrue(fields.keySet().containsAll(expectedFields), response.body());
    }

    @Test
    void ordinaryUserCannotAccessAdminSurfaces() throws Exception {
        String token = registerInvitedUser(uniqueEmail("member"));

        assertEquals(403, request("GET", "/api/v1/admin/users", null, token).status());
        assertEquals(403, request("GET", "/api/v1/backup/status", null, token).status());
        assertEquals(403, request("GET", "/api/v1/auth/invitations", null, token).status());
        assertEquals(403, request("GET", "/api/v1/audit-logs?size=1", null, token).status());
    }

    @Test
    void auditSearchUsesValidatedDatabasePagingAndExactFilters() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Audit query " + System.nanoTime());
        long actorUserId = jdbc.queryForObject(
            "SELECT id FROM users WHERE email = 'test@mamoji.com'",
            Long.class
        );
        String marker = "auditmarker" + System.nanoTime();
        for (long entityId = 901; entityId <= 903; entityId++) {
            auditLogRepository.append(new AuditEvent(
                companyId,
                "integration_audit",
                entityId,
                "verify",
                marker + " entry " + entityId,
                actorUserId,
                "Integration administrator",
                InMemoryStore.now()
            ));
        }
        auditLogRepository.append(new AuditEvent(
            companyId + 1,
            "integration_audit",
            904,
            "verify",
            marker + " different company",
            actorUserId,
            "Integration administrator",
            InMemoryStore.now()
        ));

        ApiResponse pageResponse = request(
            "GET",
            "/api/v1/audit-logs?companyId=" + companyId
                + "&entityType=integration_audit&action=verify&actorUserId=" + actorUserId
                + "&keyword=" + marker.toUpperCase() + "&page=1&size=2",
            null,
            token
        );
        assertEquals(200, pageResponse.status(), pageResponse.body());
        Map<String, Object> page = parseMap(pageResponse.body());
        assertEquals(3, ((Number) page.get("totalElements")).intValue());
        assertEquals(2, ((Number) page.get("totalPages")).intValue());
        assertEquals(1, ((Number) page.get("number")).intValue());
        assertEquals(1, ((List<?>) page.get("content")).size());

        ApiResponse exactEntity = request(
            "GET",
            "/api/v1/audit-logs?companyId=" + companyId + "&entityType=integration_audit&entityId=902",
            null,
            token
        );
        assertEquals(200, exactEntity.status(), exactEntity.body());
        assertEquals(1, ((Number) parseMap(exactEntity.body()).get("totalElements")).intValue());

        ApiResponse invalid = request(
            "GET",
            "/api/v1/audit-logs?companyId=-1&entityId=-1&actorUserId=-1&page=-1&size=201",
            null,
            token
        );
        assertValidationFields(invalid, Set.of("companyId", "entityId", "actorUserId", "page", "size"));
    }

    @Test
    void adminUserManagementOwnsItsProjectionAndProtectsTheLastAdministrator() throws Exception {
        String adminToken = text(login("test@mamoji.com", "123456").get("token"));
        long administratorId = ((Number) parseMap(request(
            "GET", "/api/v1/auth/me", null, adminToken
        ).body()).get("id")).longValue();

        ApiResponse lastAdministrator = request(
            "PUT", "/api/v1/admin/users/" + administratorId, Map.of("role", 2), adminToken
        );
        assertEquals(409, lastAdministrator.status(), lastAdministrator.body());
        assertEquals(1, jdbc.queryForObject(
            "SELECT role FROM users WHERE id = ?", Integer.class, administratorId
        ));

        String email = uniqueEmail("managed-user");
        String memberToken = registerInvitedUser(email);
        long memberId = ((Number) parseMap(request(
            "GET", "/api/v1/auth/me", null, memberToken
        ).body()).get("id")).longValue();
        ApiResponse searched = request(
            "GET", "/api/v1/admin/users?keyword=" + email + "&page=0&size=1", null, adminToken
        );
        assertEquals(200, searched.status(), searched.body());
        Map<String, Object> page = parseMap(searched.body());
        assertEquals(1, ((Number) page.get("totalElements")).intValue());
        assertFalse(searched.body().contains("passwordHash"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> managedUsers = (List<Map<String, Object>>) page.get("content");
        assertNull(managedUsers.getFirst().get("familyId"));
        assertNull(userDirectory.findById(memberId).orElseThrow().familyId());

        ApiResponse invalid = request(
            "PUT", "/api/v1/admin/users/" + memberId, Map.of("permissions", 16), adminToken
        );
        assertEquals(400, invalid.status(), invalid.body());

        ApiResponse promoted = request(
            "PUT", "/api/v1/admin/users/" + memberId, Map.of("role", 1, "permissions", 3), adminToken
        );
        assertEquals(200, promoted.status(), promoted.body());
        assertEquals(1, ((Number) parseMap(promoted.body()).get("role")).intValue());
        assertEquals(3, ((Number) parseMap(promoted.body()).get("permissions")).intValue());
        assertEquals(1, jdbc.queryForObject(
            "SELECT role FROM users WHERE id = ?", Integer.class, memberId
        ));
        assertEquals(3, jdbc.queryForObject(
            "SELECT permissions FROM users WHERE id = ?", Integer.class, memberId
        ));

        ApiResponse demoted = request(
            "PUT", "/api/v1/admin/users/" + memberId, Map.of("role", 2, "permissions", 1), adminToken
        );
        assertEquals(200, demoted.status(), demoted.body());
        assertEquals(200, request("DELETE", "/api/v1/admin/users/" + memberId, null, adminToken).status());
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, memberId
        ));
    }

    @Test
    void localUserPasswordUpgradeUsesDatabaseCompareAndSet() throws Exception {
        String email = uniqueEmail("password-cas");
        registerInvitedUser(email);
        User user = userAccounts.findByEmail(email).orElseThrow();
        String originalHash = user.passwordHash;
        String replacementHash = "replacement-hash-" + System.nanoTime();

        assertFalse(userAccounts.updatePasswordHashIfCurrent(
            user.id,
            "stale-hash",
            replacementHash,
            OffsetDateTime.now().toString()
        ));
        assertTrue(userAccounts.updatePasswordHashIfCurrent(
            user.id,
            originalHash,
            replacementHash,
            OffsetDateTime.now().toString()
        ));
        assertFalse(userAccounts.updatePasswordHashIfCurrent(
            user.id,
            originalHash,
            "must-not-win",
            OffsetDateTime.now().toString()
        ));
        assertEquals(replacementHash, userAccounts.findById(user.id).orElseThrow().passwordHash);

        assertTrue(userAccounts.updatePasswordHashIfCurrent(
            user.id,
            replacementHash,
            originalHash,
            OffsetDateTime.now().toString()
        ));
    }

    @Test
    void notificationPreferencesRejectPrivateWebhookTargets() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));

        ApiResponse response = request("PUT", "/api/v1/notifications/preferences", Map.of(
            "webhookEnabled", true,
            "webhookProvider", "generic",
            "webhookUrl", "http://169.254.169.254/latest/meta-data"
        ), token);

        assertEquals(400, response.status(), response.body());
    }

    @Test
    void freshMigrationProvidesProductionSecurityAccountingAndOvertimeSchema() {
        Set<String> employeeColumns = Set.copyOf(jdbc.queryForList("""
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = current_schema() AND table_name = 'employees'
            """, String.class));
        assertTrue(employeeColumns.containsAll(Set.of(
            "overtime_base",
            "weekday_overtime_hours",
            "rest_day_overtime_hours",
            "holiday_overtime_hours",
            "overtime_pay",
            "overtime_policy_note"
        )));

        Integer scopedTableCount = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND column_name = 'company_id'
              AND table_name IN ('accounts', 'categories', 'budgets', 'transactions', 'ledgers', 'recurring_items')
            """, Integer.class);
        assertEquals(6, scopedTableCount);
        Set<String> accountingConstraints = Set.copyOf(jdbc.queryForList("""
            SELECT conname
            FROM pg_constraint
            WHERE conname IN (
                'fk_accounts_company',
                'fk_categories_company',
                'fk_transactions_company',
                'fk_transactions_account',
                'fk_transactions_category',
                'fk_transactions_original'
            )
            """, String.class));
        assertEquals(Set.of(
            "fk_accounts_company",
            "fk_categories_company",
            "fk_transactions_company",
            "fk_transactions_account",
            "fk_transactions_category",
            "fk_transactions_original"
        ), accountingConstraints);
        assertEquals("16", jdbc.queryForObject("""
            SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank DESC LIMIT 1
            """, String.class));
        assertEquals(Set.of("created_at", "updated_at"), Set.copyOf(jdbc.queryForList("""
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'users'
              AND data_type = 'timestamp with time zone'
            """, String.class)));
        assertEquals(Set.of(
            "ck_users_email",
            "ck_users_role",
            "ck_users_permissions",
            "ck_users_password_hash",
            "ck_users_timestamps"
        ), Set.copyOf(jdbc.queryForList("""
            SELECT conname
            FROM pg_constraint
            WHERE conname LIKE 'ck_users_%'
            """, String.class)));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'outbox_events'
              AND column_name = 'lock_token'
            """, Integer.class));
        assertEquals(Set.of(
            "subject_key",
            "subject_type",
            "failed_attempts",
            "window_started_at",
            "locked_until",
            "updated_at"
        ), Set.copyOf(jdbc.queryForList("""
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'login_failure_states'
            """, String.class)));
        assertEquals(Set.of(
            "ck_login_failure_subject_key",
            "ck_login_failure_subject_type",
            "ck_login_failure_attempts",
            "ck_login_failure_lock_time"
        ), Set.copyOf(jdbc.queryForList("""
            SELECT conname
            FROM pg_constraint
            WHERE conname LIKE 'ck_login_failure_%'
            """, String.class)));
        assertEquals(Map.of(
            "created_at", "timestamp with time zone",
            "expires_at", "timestamp with time zone"
        ), jdbc.query("""
            SELECT column_name, data_type
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'auth_tokens'
              AND column_name IN ('created_at', 'expires_at')
            """, rs -> {
            Map<String, String> types = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                types.put(rs.getString("column_name"), rs.getString("data_type"));
            }
            return types;
        }));
        assertEquals(Set.of(
            "fk_auth_tokens_user",
            "ck_auth_tokens_digest",
            "ck_auth_tokens_expiry"
        ), Set.copyOf(jdbc.queryForList("""
            SELECT conname
            FROM pg_constraint
            WHERE conname IN ('fk_auth_tokens_user', 'ck_auth_tokens_digest', 'ck_auth_tokens_expiry')
            """, String.class)));
        assertEquals(Set.of(
            "expires_at",
            "accepted_at",
            "created_at",
            "updated_at"
        ), Set.copyOf(jdbc.queryForList("""
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'registration_invites'
              AND data_type = 'timestamp with time zone'
            """, String.class)));
        assertEquals(Set.of(
            "fk_registration_invites_accepted_user",
            "fk_registration_invites_inviter",
            "ck_registration_invites_digest",
            "ck_registration_invites_email",
            "ck_registration_invites_role",
            "ck_registration_invites_permissions",
            "ck_registration_invites_expiry",
            "ck_registration_invites_acceptance"
        ), Set.copyOf(jdbc.queryForList("""
            SELECT conname
            FROM pg_constraint
            WHERE conname LIKE 'fk_registration_invites_%'
               OR conname LIKE 'ck_registration_invites_%'
            """, String.class)));
    }

    @Test
    void financeRoleInOneCompanyDoesNotAuthorizeTaxWritesInAnotherCompany() throws Exception {
        String financeToken = registerInvitedUser(uniqueEmail("finance-scope"));
        Map<String, Object> financeUser = parseMap(request("GET", "/api/v1/auth/me", null, financeToken).body());
        long financeUserId = ((Number) financeUser.get("id")).longValue();
        String financeEmail = text(financeUser.get("email"));
        String adminToken = text(login("test@mamoji.com", "123456").get("token"));
        long companyA = createCompany(adminToken, "Finance Role A " + System.nanoTime());
        long companyB = createCompany(adminToken, "Viewer Role B " + System.nanoTime());
        createEmployee(adminToken, companyA, financeUserId, financeEmail, "finance_admin");
        createEmployee(adminToken, companyB, financeUserId, financeEmail, "viewer");

        ApiResponse allowed = request("POST", "/api/v1/enterprise/tax-items", Map.of(
            "companyId", companyA,
            "name", "Allowed scoped tax",
            "period", "2026-07",
            "taxType", "vat",
            "taxAmount", 1,
            "dueDate", "2026-07-31"
        ), financeToken);
        assertEquals(200, allowed.status(), allowed.body());

        ApiResponse rejected = request("POST", "/api/v1/enterprise/tax-items", Map.of(
            "companyId", companyB,
            "name", "Forbidden cross-company tax",
            "period", "2026-07",
            "taxType", "vat",
            "taxAmount", 1,
            "dueDate", "2026-07-31"
        ), financeToken);
        assertEquals(403, rejected.status(), rejected.body());
    }

    @Test
    void departedEmployeeImmediatelyLosesCompanyAndFinanceAccess() throws Exception {
        String employeeToken = registerInvitedUser(uniqueEmail("departed-finance"));
        Map<String, Object> employeeUser = parseMap(request("GET", "/api/v1/auth/me", null, employeeToken).body());
        long userId = ((Number) employeeUser.get("id")).longValue();
        String email = text(employeeUser.get("email"));
        String adminToken = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(adminToken, "Departure Access " + System.nanoTime());
        Map<String, Object> employee = createEmployee(adminToken, companyId, userId, email, "finance_admin");
        long employeeId = ((Number) employee.get("id")).longValue();

        assertEquals(200, request("GET", "/api/v1/enterprise/company?companyId=" + companyId, null, employeeToken).status());
        ApiResponse departure = request("PUT", "/api/v1/enterprise/employees/" + employeeId, Map.of(
            "status", "departed",
            "leaveDate", "2026-07-14"
        ), adminToken);
        assertEquals(200, departure.status(), departure.body());

        assertEquals(403, request("GET", "/api/v1/enterprise/company?companyId=" + companyId, null, employeeToken).status());
        ApiResponse taxWrite = request("POST", "/api/v1/enterprise/tax-items", Map.of(
            "companyId", companyId,
            "name", "Must remain forbidden after departure",
            "period", "2026-07",
            "taxType", "vat",
            "taxAmount", 1,
            "dueDate", "2026-07-31"
        ), employeeToken);
        assertEquals(403, taxWrite.status(), taxWrite.body());
    }

    @Test
    void readonlyFinanceRoleCannotUseCompanyWideWritePermissions() throws Exception {
        String employeeToken = registerInvitedUser(uniqueEmail("readonly-finance"));
        Map<String, Object> employeeUser = parseMap(request("GET", "/api/v1/auth/me", null, employeeToken).body());
        long userId = ((Number) employeeUser.get("id")).longValue();
        String adminToken = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(adminToken, "Readonly Finance " + System.nanoTime());
        createEmployee(
            adminToken,
            companyId,
            userId,
            text(employeeUser.get("email")),
            "finance_admin",
            "readonly"
        );

        ApiResponse taxWrite = request("POST", "/api/v1/enterprise/tax-items", Map.of(
            "companyId", companyId,
            "name", "Readonly role must not write",
            "period", "2026-07",
            "taxType", "vat",
            "taxAmount", 1,
            "dueDate", "2026-07-31"
        ), employeeToken);
        assertEquals(403, taxWrite.status(), taxWrite.body());
    }

    @Test
    void workspaceAppliesDepartmentScopeInsideAggregateQueries() throws Exception {
        YearMonth currentPeriod = YearMonth.now();
        String adminToken = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(adminToken, "Workspace Scope " + System.nanoTime());
        long departmentA = createDepartment(adminToken, companyId, "Workspace A");
        long departmentB = createDepartment(adminToken, companyId, "Workspace B");

        String managerToken = registerInvitedUser(uniqueEmail("workspace-manager"));
        Map<String, Object> manager = parseMap(request("GET", "/api/v1/auth/me", null, managerToken).body());
        createEmployee(adminToken, companyId, ((Number) manager.get("id")).longValue(), text(manager.get("email")),
            "department_manager", "department", departmentA);

        String peerToken = registerInvitedUser(uniqueEmail("workspace-peer"));
        Map<String, Object> peer = parseMap(request("GET", "/api/v1/auth/me", null, peerToken).body());
        createEmployee(adminToken, companyId, ((Number) peer.get("id")).longValue(), text(peer.get("email")),
            "employee", "self", departmentA);

        String outsiderToken = registerInvitedUser(uniqueEmail("workspace-outsider"));
        Map<String, Object> outsider = parseMap(request("GET", "/api/v1/auth/me", null, outsiderToken).body());
        createEmployee(adminToken, companyId, ((Number) outsider.get("id")).longValue(), text(outsider.get("email")),
            "employee", "self", departmentB);

        Map<String, Object> peerAccount = createAccount(peerToken, companyId, "Department A account", "1000");
        Map<String, Object> peerCategory = createCategory(peerToken, companyId, "Department A expense", "expense");
        createTransaction(peerToken, companyId, peerAccount, peerCategory, "40", currentPeriod.atDay(14).toString());

        Map<String, Object> outsiderAccount = createAccount(outsiderToken, companyId, "Department B account", "1000");
        Map<String, Object> outsiderCategory = createCategory(outsiderToken, companyId, "Department B expense", "expense");
        createTransaction(outsiderToken, companyId, outsiderAccount, outsiderCategory, "90", currentPeriod.atDay(14).toString());

        ApiResponse contextResponse = request(
            "GET", "/api/v1/platform/access-context?companyId=" + companyId, null, managerToken
        );
        assertEquals(200, contextResponse.status(), contextResponse.body());
        assertEquals(departmentA, ((Number) parseMap(contextResponse.body()).get("departmentId")).longValue());

        ApiResponse response = request("GET", "/api/v1/workspace?companyId=" + companyId, null, managerToken);
        assertEquals(200, response.status(), response.body());
        Map<String, Object> workspace = parseMap(response.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) workspace.get("metrics");
        assertEquals(0, new BigDecimal("40").compareTo(decimal(metrics.get("monthlyExpense"))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recentTransactions = (List<Map<String, Object>>) workspace.get("recentTransactions");
        assertEquals(1, recentTransactions.size());
        assertEquals(0, new BigDecimal("40").compareTo(decimal(recentTransactions.getFirst().get("amount"))));

        ApiResponse workforceResponse = request(
            "GET", "/api/v1/workforce-cost?companyId=" + companyId + "&period=" + currentPeriod, null, managerToken
        );
        assertEquals(200, workforceResponse.status(), workforceResponse.body());
        Map<String, Object> workforce = parseMap(workforceResponse.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> headcount = (Map<String, Object>) workforce.get("headcount");
        assertEquals(2, ((Number) headcount.get("costed")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> costs = (Map<String, Object>) workforce.get("costs");
        assertEquals(0, new BigDecimal("40").compareTo(decimal(costs.get("operatingExpense"))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> departments = (List<Map<String, Object>>) workforce.get("departments");
        assertEquals(1, departments.size());
        assertEquals(departmentA, ((Number) departments.getFirst().get("departmentId")).longValue());
    }

    @Test
    void concurrentAdministratorDemotionsAlwaysLeaveOneAdministrator() throws Exception {
        String token = adminToken();
        long primaryAdministratorId = jdbc.queryForObject(
            "SELECT id FROM users WHERE email = 'test@mamoji.com'",
            Long.class
        );
        long secondAdministratorId = jdbc.queryForObject("""
            INSERT INTO users (
                email, nickname, avatar, family_id, role, permissions, password_hash, created_at, updated_at
            )
            SELECT ?, 'Concurrent administrator', avatar, family_id, 1, permissions, password_hash,
                   created_at, updated_at
            FROM users
            WHERE id = ?
            RETURNING id
            """, Long.class, "concurrent-admin-" + System.nanoTime() + "@example.invalid", primaryAdministratorId);

        try {
            CompletableFuture<ApiResponse> first = requestAsync(
                "PUT", "/api/v1/admin/users/" + primaryAdministratorId, Map.of("role", 2), token
            );
            CompletableFuture<ApiResponse> second = requestAsync(
                "PUT", "/api/v1/admin/users/" + secondAdministratorId, Map.of("role", 2), token
            );

            ApiResponse firstResponse = first.get(10, TimeUnit.SECONDS);
            ApiResponse secondResponse = second.get(10, TimeUnit.SECONDS);
            List<Integer> statuses = List.of(firstResponse.status(), secondResponse.status());

            assertEquals(1, statuses.stream().filter(status -> status == 200).count(),
                firstResponse.body() + " / " + secondResponse.body());
            assertTrue(statuses.stream().anyMatch(status -> status == 403 || status == 409),
                firstResponse.body() + " / " + secondResponse.body());
            assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 1", Integer.class
            ));
        } finally {
            java.time.OffsetDateTime restoredAt = java.time.OffsetDateTime.now();
            jdbc.update("UPDATE users SET role = 1, updated_at = ? WHERE id = ?", restoredAt, primaryAdministratorId);
            jdbc.update("DELETE FROM users WHERE id = ?", secondAdministratorId);
        }
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value)
        );
    }
}
