package com.mamoji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mamoji.platform.audit.application.AuditLogRepository;
import com.mamoji.platform.audit.domain.AuditEvent;
import com.mamoji.platform.identity.security.application.LoginFailureRepository;
import com.mamoji.platform.identity.security.application.LoginSecurityService;
import com.mamoji.platform.identity.security.domain.LoginThrottleSubject;
import com.mamoji.repository.InMemoryStore;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

        ApiResponse me = request("GET", "/api/v1/auth/me", null, token);
        assertEquals(200, me.status());
        assertFalse(me.body().contains("passwordHash"));

        ApiResponse logout = request("POST", "/api/v1/auth/logout", null, token);
        assertEquals(200, logout.status());

        ApiResponse meAfterLogout = request("GET", "/api/v1/auth/me", null, token);
        assertEquals(401, meAfterLogout.status());
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
        assertEquals(1, coreStore.users.get(memberId).role);
        assertEquals(3, coreStore.users.get(memberId).permissions);

        ApiResponse demoted = request(
            "PUT", "/api/v1/admin/users/" + memberId, Map.of("role", 2, "permissions", 1), adminToken
        );
        assertEquals(200, demoted.status(), demoted.body());
        assertEquals(200, request("DELETE", "/api/v1/admin/users/" + memberId, null, adminToken).status());
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, memberId
        ));
        assertFalse(coreStore.users.containsKey(memberId));
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
        assertEquals("11", jdbc.queryForObject("""
            SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank DESC LIMIT 1
            """, String.class));
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
            String restoredAt = java.time.OffsetDateTime.now().toString();
            jdbc.update("UPDATE users SET role = 1, updated_at = ? WHERE id = ?", restoredAt, primaryAdministratorId);
            jdbc.update("DELETE FROM users WHERE id = ?", secondAdministratorId);
            int permissions = jdbc.queryForObject(
                "SELECT permissions FROM users WHERE id = ?", Integer.class, primaryAdministratorId
            );
            coreStore.synchronizeUserAccessAfterCommit(primaryAdministratorId, 1, permissions, restoredAt);
        }
    }
}
