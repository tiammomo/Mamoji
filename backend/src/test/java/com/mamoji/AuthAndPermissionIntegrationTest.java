package com.mamoji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.repository.InMemoryStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "mamoji.runtime.environment=local",
    "mamoji.schema.compatibility-enabled=false",
    "mamoji.bootstrap.mode=demo",
    "mamoji.registration.mode=invite",
    "mamoji.security.password.min-length=12",
    "mamoji.security.password.require-complexity=true",
    "mamoji.object-storage.enabled=false",
    "mamoji.outbox.consumer.enabled=false",
    "mamoji.notifications.reminder.enabled=false",
    "mamoji.notifications.delivery.enabled=false",
    "debug=false",
    "logging.level.root=INFO",
    "spring.main.log-startup-info=false",
    "logging.level.org.springframework.web=INFO",
    "logging.level.org.springframework.jdbc.core=INFO"
})
class AuthAndPermissionIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST_TYPE = new TypeReference<>() {
    };

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InMemoryStore coreStore;

    @Autowired
    DataSource dataSource;

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

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
            "password", password,
            "inviteToken", inviteToken
        ), null);
        assertEquals(200, registered.status());
        Map<String, Object> session = parseMap(registered.body());
        assertNotNull(session.get("token"));
        assertFalse(registered.body().contains("passwordHash"));
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
    void freshMigrationProvidesProductionAccountingAndOvertimeColumns() {
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
        assertEquals("7", jdbc.queryForObject("""
            SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank DESC LIMIT 1
            """, String.class));
    }

    @Test
    void crossOwnerTransactionIsRejectedWithoutChangingBalanceOrCreatingData() throws Exception {
        String memberToken = registerInvitedUser(uniqueEmail("account-owner"));
        long companyId = createCompany(memberToken, "Owner Scope " + System.nanoTime());
        Map<String, Object> account = createAccount(memberToken, companyId, "Member account", "1000");
        Map<String, Object> category = createCategory(memberToken, companyId, "Member expense", "expense");
        long accountId = ((Number) account.get("id")).longValue();
        long categoryId = ((Number) category.get("id")).longValue();

        createTransaction(memberToken, companyId, account, category, "5");
        long beforeCount = transactionCount(memberToken, companyId);
        Map<String, Object> beforeAccount = parseMap(request(
            "GET",
            "/api/v1/accounts/" + accountId + "?companyId=" + companyId,
            null,
            memberToken
        ).body());
        BigDecimal beforeBalance = decimal(beforeAccount.get("balance"));
        String adminToken = text(login("test@mamoji.com", "123456").get("token"));
        assertEquals(0, transactionCount(adminToken, companyId), "Accounting data is intentionally scoped by both company and user");
        ApiResponse rejected = request("POST", "/api/v1/transactions", Map.of(
            "companyId", companyId,
            "type", 2,
            "amount", 120,
            "accountId", accountId,
            "categoryId", categoryId,
            "date", "2026-07-14",
            "note", "must be rejected"
        ), adminToken);

        assertEquals(403, rejected.status(), rejected.body());
        assertEquals(beforeCount, transactionCount(memberToken, companyId));
        Map<String, Object> afterAccount = parseMap(request(
            "GET",
            "/api/v1/accounts/" + accountId + "?companyId=" + companyId,
            null,
            memberToken
        ).body());
        assertEquals(0, beforeBalance.compareTo(decimal(afterAccount.get("balance"))));
    }

    @Test
    void accountingQueriesAndStatisticsAreIsolatedByCompany() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyA = createCompany(token, "Scope A " + System.nanoTime());
        long companyB = createCompany(token, "Scope B " + System.nanoTime());
        Map<String, Object> accountA = createAccount(token, companyA, "A account", "1000");
        Map<String, Object> accountB = createAccount(token, companyB, "B account", "2000");
        Map<String, Object> categoryA = createCategory(token, companyA, "A expense", "expense");
        Map<String, Object> categoryB = createCategory(token, companyB, "B expense", "expense");

        Map<String, Object> transactionA = createTransaction(token, companyA, accountA, categoryA, "11");
        createTransaction(token, companyB, accountB, categoryB, "29");

        @SuppressWarnings("unchecked")
        Map<String, Object> riskA = (Map<String, Object>) transactionA.get("risk");
        assertEquals(0, new BigDecimal("11").compareTo(decimal(riskA.get("monthlyExpense"))),
            "Risk assessment must include the transaction being created before its cache entry is committed");

        Map<String, Object> statsA = parseMap(request("GET", "/api/v1/stats/overview?month=2026-07&companyId=" + companyA, null, token).body());
        Map<String, Object> statsB = parseMap(request("GET", "/api/v1/stats/overview?month=2026-07&companyId=" + companyB, null, token).body());
        assertEquals(0, new BigDecimal("11").compareTo(decimal(statsA.get("monthlyExpense"))));
        assertEquals(0, new BigDecimal("29").compareTo(decimal(statsB.get("monthlyExpense"))));
        assertEquals(1, transactionCount(token, companyA));
        assertEquals(1, transactionCount(token, companyB));

        List<Map<String, Object>> accountsA = parseList(request("GET", "/api/v1/accounts?companyId=" + companyA, null, token).body());
        assertTrue(accountsA.stream().allMatch(item -> ((Number) item.get("companyId")).longValue() == companyA));
        assertFalse(accountsA.stream().anyMatch(item -> ((Number) item.get("id")).longValue() == ((Number) accountB.get("id")).longValue()));
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
    void accountMetadataUpdateUsesLockedDatabaseBalanceInsteadOfStaleCache() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Balance Lock " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Lock account", "1000");
        long accountId = ((Number) account.get("id")).longValue();
        jdbc.update("UPDATE accounts SET balance = '875', available_balance = '875' WHERE id = ?", accountId);

        ApiResponse updated = request(
            "PUT",
            "/api/v1/accounts/" + accountId + "?companyId=" + companyId,
            Map.of("name", "Renamed without balance loss"),
            token
        );
        assertEquals(200, updated.status(), updated.body());
        Map<String, Object> updatedAccount = parseMap(updated.body());
        assertEquals(0, new BigDecimal("875").compareTo(decimal(updatedAccount.get("balance"))));
        assertEquals("875", jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", String.class, accountId));
    }

    @Test
    void accountDeletionChecksCommittedDatabaseReferencesEvenWhenCacheEntryIsMissing() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Reference Lock " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Referenced account", "1000");
        Map<String, Object> category = createCategory(token, companyId, "Referenced expense", "expense");
        Map<String, Object> created = createTransaction(token, companyId, account, category, "10");
        @SuppressWarnings("unchecked")
        Map<String, Object> transaction = (Map<String, Object>) created.get("transaction");
        long transactionId = ((Number) transaction.get("id")).longValue();
        long accountId = ((Number) account.get("id")).longValue();
        TransactionRecord cached = coreStore.transactions.remove(transactionId);
        try {
            ApiResponse deleted = request(
                "DELETE",
                "/api/v1/accounts/" + accountId + "?companyId=" + companyId,
                null,
                token
            );
            assertEquals(409, deleted.status(), deleted.body());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE id = ?", Integer.class, accountId));
        } finally {
            if (cached != null) {
                coreStore.transactions.put(transactionId, cached);
            }
        }
    }

    @Test
    void concurrentTransactionWinsBeforeAccountDeletionAndPreventsOrphan() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Concurrent Account Delete " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Concurrent account", "1000");
        Map<String, Object> category = createCategory(token, companyId, "Concurrent category", "expense");
        long accountId = ((Number) account.get("id")).longValue();
        long categoryId = ((Number) category.get("id")).longValue();

        try (Connection blocker = lockRow("SELECT id FROM categories WHERE id = ? FOR UPDATE", categoryId)) {
            CompletableFuture<ApiResponse> create = requestAsync("POST", "/api/v1/transactions", Map.of(
                "companyId", companyId,
                "type", 2,
                "amount", 10,
                "accountId", accountId,
                "categoryId", categoryId,
                "date", "2026-07-14",
                "note", "concurrent account delete"
            ), token);
            awaitBlockedQuery("categories");
            CompletableFuture<ApiResponse> delete = requestAsync(
                "DELETE",
                "/api/v1/accounts/" + accountId + "?companyId=" + companyId,
                null,
                token
            );
            blocker.commit();

            ApiResponse created = create.get(10, TimeUnit.SECONDS);
            ApiResponse deleted = delete.get(10, TimeUnit.SECONDS);
            assertEquals(200, created.status(), created.body());
            assertEquals(409, deleted.status(), deleted.body());
        }
        assertEquals(1, transactionCount(token, companyId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE id = ?", Integer.class, accountId));
    }

    @Test
    void concurrentCategoryDeletionWinsBeforeTransactionAndPreventsOrphan() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Concurrent Category Delete " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Blocked account", "1000");
        Map<String, Object> category = createCategory(token, companyId, "Deleted category", "expense");
        long accountId = ((Number) account.get("id")).longValue();
        long categoryId = ((Number) category.get("id")).longValue();

        CompletableFuture<ApiResponse> create;
        try (Connection blocker = lockRow("SELECT id FROM accounts WHERE id = ? FOR UPDATE", accountId)) {
            create = requestAsync("POST", "/api/v1/transactions", Map.of(
                "companyId", companyId,
                "type", 2,
                "amount", 10,
                "accountId", accountId,
                "categoryId", categoryId,
                "date", "2026-07-14",
                "note", "concurrent category delete"
            ), token);
            awaitBlockedQuery("accounts");
            ApiResponse deleted = request(
                "DELETE",
                "/api/v1/categories/" + categoryId + "?companyId=" + companyId,
                null,
                token
            );
            assertEquals(200, deleted.status(), deleted.body());
            blocker.commit();
        }

        ApiResponse created = create.get(10, TimeUnit.SECONDS);
        assertEquals(400, created.status(), created.body());
        assertEquals(0, transactionCount(token, companyId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM categories WHERE id = ?", Integer.class, categoryId));
    }

    @Test
    void receiptCannotLinkTransactionFromAnotherCompany() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyA = createCompany(token, "Receipt Scope A " + System.nanoTime());
        long companyB = createCompany(token, "Receipt Scope B " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyA, "Receipt A account", "1000");
        Map<String, Object> category = createCategory(token, companyA, "Receipt A expense", "expense");
        Map<String, Object> created = createTransaction(token, companyA, account, category, "20");
        @SuppressWarnings("unchecked")
        Map<String, Object> transaction = (Map<String, Object>) created.get("transaction");

        ApiResponse response = request("POST", "/api/v1/receipts", Map.of(
            "companyId", companyB,
            "transactionId", transaction.get("id"),
            "title", "Cross-company voucher must fail",
            "amount", 20,
            "issueDate", "2026-07-14"
        ), token);
        assertEquals(403, response.status(), response.body());
    }

    @Test
    void refundedTransactionCannotMoveToAnotherAccount() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Refund Edit " + System.nanoTime());
        Map<String, Object> accountA = createAccount(token, companyId, "Refund source", "1000");
        Map<String, Object> accountB = createAccount(token, companyId, "Refund target", "500");
        Map<String, Object> category = createCategory(token, companyId, "Refund edit expense", "expense");
        Map<String, Object> created = createTransaction(token, companyId, accountA, category, "100");
        @SuppressWarnings("unchecked")
        Map<String, Object> original = (Map<String, Object>) created.get("transaction");
        long originalId = ((Number) original.get("id")).longValue();
        assertEquals(200, request("POST", "/api/v1/transactions/" + originalId + "/refund", Map.of(
            "companyId", companyId,
            "amount", 30,
            "date", "2026-07-14"
        ), token).status());

        ApiResponse moved = request(
            "PUT",
            "/api/v1/transactions/" + originalId + "?companyId=" + companyId,
            Map.of("accountId", accountB.get("id")),
            token
        );
        assertEquals(409, moved.status(), moved.body());
        assertAccountBalances(token, companyId, ((Number) accountA.get("id")).longValue(), "930", "930");
        assertAccountBalances(token, companyId, ((Number) accountB.get("id")).longValue(), "500", "500");
    }

    @Test
    void recurringItemCannotPostTwiceOnTheSameDay() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Recurring Lock " + System.nanoTime());
        createAccount(token, companyId, "Recurring account", "1000");
        createCategory(token, companyId, "Recurring expense", "expense");
        ApiResponse created = request("POST", "/api/v1/recurring", Map.of(
            "companyId", companyId,
            "name", "Once per day",
            "type", 2,
            "amount", 15,
            "frequency", "daily",
            "interval", 1,
            "startDate", "2026-07-14"
        ), token);
        assertEquals(200, created.status(), created.body());
        String recurringId = text(parseMap(created.body()).get("id"));

        assertEquals(200, request(
            "POST", "/api/v1/recurring/" + recurringId + "/execute?companyId=" + companyId, null, token
        ).status());
        ApiResponse duplicate = request(
            "POST", "/api/v1/recurring/" + recurringId + "/execute?companyId=" + companyId, null, token
        );
        assertEquals(409, duplicate.status(), duplicate.body());
        assertEquals(1, transactionCount(token, companyId));
    }

    @Test
    void companyMemberCanSubmitReimbursementButCannotManageFinanceVouchers() throws Exception {
        String memberToken = registerInvitedUser(uniqueEmail("reimbursement-member"));
        Map<String, Object> member = parseMap(request("GET", "/api/v1/auth/me", null, memberToken).body());
        long memberUserId = ((Number) member.get("id")).longValue();
        String memberEmail = text(member.get("email"));
        String adminToken = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(adminToken, "Reimbursement Scope " + System.nanoTime());
        createEmployee(adminToken, companyId, memberUserId, memberEmail, "viewer");

        ApiResponse reimbursement = request("POST", "/api/v1/receipts", Map.of(
            "companyId", companyId,
            "title", "Member travel reimbursement",
            "voucherType", "reimbursement",
            "direction", "expense",
            "counterparty", memberEmail,
            "amount", 88
        ), memberToken);
        assertEquals(200, reimbursement.status(), reimbursement.body());
        long reimbursementId = ((Number) parseMap(reimbursement.body()).get("id")).longValue();

        ApiResponse approvalSubmission = request("POST", "/api/v1/approvals", Map.of(
            "companyId", companyId,
            "requestType", "reimbursement",
            "entityType", "receipt_voucher",
            "entityId", reimbursementId,
            "title", "Member travel reimbursement approval",
            "amount", 88
        ), memberToken);
        assertEquals(200, approvalSubmission.status(), approvalSubmission.body());
        assertEquals("pending", jdbc.queryForObject(
            "SELECT approval_status FROM receipt_vouchers WHERE id = ?", String.class, reimbursementId
        ));

        ApiResponse financeVoucher = request("POST", "/api/v1/receipts", Map.of(
            "companyId", companyId,
            "title", "Member must not create purchase invoice",
            "voucherType", "purchase_invoice",
            "direction", "expense",
            "counterparty", "Supplier",
            "amount", 88
        ), memberToken);
        assertEquals(403, financeVoucher.status(), financeVoucher.body());

        ApiResponse workflowUpdate = request("PUT", "/api/v1/receipts/" + reimbursementId, Map.of(
            "status", "verified"
        ), memberToken);
        assertEquals(403, workflowUpdate.status(), workflowUpdate.body());
    }

    @Test
    void receiptApprovalCannotBeBypassedAndPostingWaitsForDecision() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Approval Flow " + System.nanoTime());
        ApiResponse created = request("POST", "/api/v1/receipts", Map.of(
            "companyId", companyId,
            "title", "Approval required reimbursement",
            "voucherType", "reimbursement",
            "direction", "expense",
            "counterparty", "Employee",
            "amount", 6800,
            "issueDate", "2026-07-14"
        ), token);
        assertEquals(200, created.status(), created.body());
        Map<String, Object> voucher = parseMap(created.body());
        long voucherId = ((Number) voucher.get("id")).longValue();
        assertEquals("not_submitted", voucher.get("approvalStatus"));

        ApiResponse bypass = request("PUT", "/api/v1/receipts/" + voucherId, Map.of(
            "approvalStatus", "approved"
        ), token);
        assertEquals(400, bypass.status(), bypass.body());

        ApiResponse earlyPosting = request("PUT", "/api/v1/receipts/" + voucherId, Map.of(
            "accountingStatus", "posted"
        ), token);
        assertEquals(409, earlyPosting.status(), earlyPosting.body());

        ApiResponse submitted = request("POST", "/api/v1/approvals", Map.of(
            "companyId", companyId,
            "requestType", "reimbursement",
            "entityType", "receipt_voucher",
            "entityId", voucherId,
            "title", "Reimbursement approval",
            "amount", 6800
        ), token);
        assertEquals(200, submitted.status(), submitted.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> approvalRequest = (Map<String, Object>) parseMap(submitted.body()).get("request");
        long approvalId = ((Number) approvalRequest.get("id")).longValue();
        assertEquals("pending", jdbc.queryForObject("SELECT approval_status FROM receipt_vouchers WHERE id = ?", String.class, voucherId));

        ApiResponse approved = request("POST", "/api/v1/approvals/" + approvalId + "/approve", Map.of(
            "comment", "Evidence checked"
        ), token);
        assertEquals(200, approved.status(), approved.body());
        assertEquals("approved", jdbc.queryForObject("SELECT approval_status FROM receipt_vouchers WHERE id = ?", String.class, voucherId));

        ApiResponse posted = request("PUT", "/api/v1/receipts/" + voucherId, Map.of(
            "accountingStatus", "posted"
        ), token);
        assertEquals(200, posted.status(), posted.body());
        assertEquals("posted", parseMap(posted.body()).get("accountingStatus"));
    }

    @Test
    void accountReconciliationCreatesImmutableSnapshotAndUpdatesRiskState() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Reconciliation Flow " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Statement account", "1000");
        long accountId = ((Number) account.get("id")).longValue();

        ApiResponse reconciled = request("POST", "/api/v1/accounts/" + accountId + "/reconciliations?companyId=" + companyId, Map.of(
            "statementDate", "2026-07-14",
            "statementBalance", 990,
            "note", "Bank statement"
        ), token);
        assertEquals(200, reconciled.status(), reconciled.body());
        Map<String, Object> snapshot = parseMap(reconciled.body());
        assertEquals("exception", snapshot.get("status"));
        assertEquals(0, new BigDecimal("-10.00").compareTo(decimal(snapshot.get("difference"))));

        ApiResponse current = request("GET", "/api/v1/accounts/" + accountId + "?companyId=" + companyId, null, token);
        assertEquals("exception", parseMap(current.body()).get("reconciliationStatus"));
        ApiResponse bypass = request("PUT", "/api/v1/accounts/" + accountId + "?companyId=" + companyId, Map.of(
            "reconciliationStatus", "reconciled"
        ), token);
        assertEquals(400, bypass.status(), bypass.body());
    }

    @Test
    void globalSearchReturnsCompanyScopedBusinessRecords() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Search Flow " + System.nanoTime());
        String needle = "Needle" + System.nanoTime();
        createAccount(token, companyId, needle, "1234");

        ApiResponse response = request("GET", "/api/v1/search?companyId=" + companyId + "&keyword=" + needle, null, token);
        assertEquals(200, response.status(), response.body());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) parseMap(response.body()).get("results");
        assertTrue(results.stream().anyMatch(result -> "account".equals(result.get("type")) && needle.equals(result.get("title"))));
    }

    @Test
    void refundAndRefundDeletionKeepBalancesBudgetsAndOriginalTransactionConsistent() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Refund Scope " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Refund account", "1000");
        Map<String, Object> category = createCategory(token, companyId, "Refund expense", "expense");
        long accountId = ((Number) account.get("id")).longValue();
        long categoryId = ((Number) category.get("id")).longValue();
        ApiResponse budgetResponse = request("POST", "/api/v1/budgets", Map.of(
            "companyId", companyId,
            "name", "Refund budget",
            "amount", 100,
            "categoryId", categoryId,
            "startDate", "2026-07-01",
            "endDate", "2026-07-31",
            "warningThreshold", 85
        ), token);
        assertEquals(200, budgetResponse.status(), budgetResponse.body());

        Map<String, Object> created = createTransaction(token, companyId, account, category, "80");
        @SuppressWarnings("unchecked")
        Map<String, Object> original = (Map<String, Object>) created.get("transaction");
        long originalId = ((Number) original.get("id")).longValue();
        assertAccountBalances(token, companyId, accountId, "920", "920");
        assertEquals(0, new BigDecimal("80").compareTo(budgetSpent(token, companyId)));

        ApiResponse refundResponse = request("POST", "/api/v1/transactions/" + originalId + "/refund", Map.of(
            "companyId", companyId,
            "amount", 30,
            "date", "2026-07-15"
        ), token);
        assertEquals(200, refundResponse.status(), refundResponse.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> refund = (Map<String, Object>) parseMap(refundResponse.body()).get("transaction");
        long refundId = ((Number) refund.get("id")).longValue();
        assertAccountBalances(token, companyId, accountId, "950", "950");
        assertEquals(0, new BigDecimal("50").compareTo(budgetSpent(token, companyId)));
        Map<String, Object> afterRefundOriginal = parseMap(request(
            "GET", "/api/v1/transactions/" + originalId + "?companyId=" + companyId, null, token
        ).body());
        assertEquals(0, new BigDecimal("30").compareTo(decimal(afterRefundOriginal.get("refundedAmount"))));

        ApiResponse deleted = request("DELETE", "/api/v1/transactions/" + refundId + "?companyId=" + companyId, null, token);
        assertEquals(200, deleted.status(), deleted.body());
        assertAccountBalances(token, companyId, accountId, "920", "920");
        assertEquals(0, new BigDecimal("80").compareTo(budgetSpent(token, companyId)));
        Map<String, Object> afterDeleteOriginal = parseMap(request(
            "GET", "/api/v1/transactions/" + originalId + "?companyId=" + companyId, null, token
        ).body());
        assertEquals(0, BigDecimal.ZERO.compareTo(decimal(afterDeleteOriginal.get("refundedAmount"))));
        assertTrue(Boolean.TRUE.equals(afterDeleteOriginal.get("isRefundable")));
    }

    private long createCompany(String token, String name) throws Exception {
        ApiResponse response = request("POST", "/api/v1/enterprise/companies", Map.of(
            "name", name,
            "entityType", "company",
            "currency", "CNY",
            "industry", "integration-test",
            "taxpayerType", "test"
        ), token);
        assertEquals(200, response.status(), response.body());
        return ((Number) parseMap(response.body()).get("id")).longValue();
    }

    private Map<String, Object> createAccount(String token, long companyId, String name, String balance) throws Exception {
        ApiResponse response = request("POST", "/api/v1/accounts", Map.of(
            "companyId", companyId,
            "name", name,
            "type", "bank",
            "balance", balance
        ), token);
        assertEquals(200, response.status(), response.body());
        return parseMap(response.body());
    }

    private Map<String, Object> createCategory(String token, long companyId, String name, String type) throws Exception {
        ApiResponse response = request("POST", "/api/v1/categories", Map.of(
            "companyId", companyId,
            "name", name,
            "type", type,
            "icon", "T",
            "color", "#000000"
        ), token);
        assertEquals(200, response.status(), response.body());
        return parseMap(response.body());
    }

    private Map<String, Object> createEmployee(
        String token,
        long companyId,
        long userId,
        String email,
        String accessRole
    ) throws Exception {
        return createEmployee(token, companyId, userId, email, accessRole, "company");
    }

    private Map<String, Object> createEmployee(
        String token,
        long companyId,
        long userId,
        String email,
        String accessRole,
        String accessScope
    ) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("companyId", companyId);
        body.put("userId", userId);
        body.put("name", "Scoped " + accessRole);
        body.put("email", email);
        body.put("position", accessRole);
        body.put("employmentType", "full_time");
        body.put("status", "active");
        body.put("accessRole", accessRole);
        body.put("accessScope", accessScope);
        body.put("hireDate", "2026-07-01");
        body.put("salary", 0);
        ApiResponse response = request("POST", "/api/v1/enterprise/employees", body, token);
        assertEquals(200, response.status(), response.body());
        return parseMap(response.body());
    }

    private Connection lockRow(String sql, long id) throws Exception {
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, id);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next(), "Expected row to lock");
                }
            }
            return connection;
        } catch (Exception ex) {
            connection.close();
            throw ex;
        }
    }

    private void awaitBlockedQuery(String table) throws Exception {
        for (int attempt = 0; attempt < 250; attempt++) {
            Integer blocked = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM pg_stat_activity
                WHERE wait_event_type = 'Lock'
                  AND query LIKE ?
                """, Integer.class, "%FROM " + table + "%FOR UPDATE%");
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for blocked " + table + " query");
    }

    private CompletableFuture<ApiResponse> requestAsync(String method, String path, Object body, String token) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return request(method, path, body, token);
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        });
    }

    private Map<String, Object> createTransaction(
        String token,
        long companyId,
        Map<String, Object> account,
        Map<String, Object> category,
        String amount
    ) throws Exception {
        ApiResponse response = request("POST", "/api/v1/transactions", Map.of(
            "companyId", companyId,
            "type", 2,
            "amount", amount,
            "accountId", account.get("id"),
            "categoryId", category.get("id"),
            "date", "2026-07-14",
            "note", "company scope test"
        ), token);
        assertEquals(200, response.status(), response.body());
        return parseMap(response.body());
    }

    private long transactionCount(String token, long companyId) throws Exception {
        ApiResponse response = request("GET", "/api/v1/transactions?companyId=" + companyId + "&size=200", null, token);
        assertEquals(200, response.status(), response.body());
        return ((Number) parseMap(response.body()).get("totalElements")).longValue();
    }

    private void assertAccountBalances(
        String token,
        long companyId,
        long accountId,
        String expectedBalance,
        String expectedAvailableBalance
    ) throws Exception {
        ApiResponse response = request("GET", "/api/v1/accounts/" + accountId + "?companyId=" + companyId, null, token);
        assertEquals(200, response.status(), response.body());
        Map<String, Object> account = parseMap(response.body());
        assertEquals(0, new BigDecimal(expectedBalance).compareTo(decimal(account.get("balance"))));
        assertEquals(0, new BigDecimal(expectedAvailableBalance).compareTo(decimal(account.get("availableBalance"))));
    }

    private BigDecimal budgetSpent(String token, long companyId) throws Exception {
        ApiResponse response = request("GET", "/api/v1/budgets?companyId=" + companyId + "&size=200", null, token);
        assertEquals(200, response.status(), response.body());
        Object content = parseMap(response.body()).get("content");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> budgets = (List<Map<String, Object>>) content;
        assertEquals(1, budgets.size());
        return decimal(budgets.get(0).get("spent"));
    }

    private BigDecimal decimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private Map<String, Object> login(String email, String password) throws Exception {
        ApiResponse response = request("POST", "/api/v1/auth/login", Map.of(
            "email", email,
            "password", password
        ), null);
        assertEquals(200, response.status(), response.body());
        return parseMap(response.body());
    }

    private String registerInvitedUser(String email) throws Exception {
        String password = "Member-Password-123!";
        String inviteToken = createInvite(email, Permissions.USER);
        ApiResponse response = request("POST", "/api/v1/auth/register", Map.of(
            "email", email,
            "nickname", "Member",
            "password", password,
            "inviteToken", inviteToken
        ), null);
        assertEquals(200, response.status(), response.body());
        return text(parseMap(response.body()).get("token"));
    }

    private String createInvite(String email, int permissions) throws Exception {
        String adminToken = text(login("test@mamoji.com", "123456").get("token"));
        ApiResponse invite = request("POST", "/api/v1/auth/invitations", Map.of(
            "email", email,
            "role", Roles.USER,
            "permissions", permissions,
            "expiresInDays", 1
        ), adminToken);
        assertEquals(200, invite.status(), invite.body());
        return text(parseMap(invite.body()).get("token"));
    }

    private ApiResponse request(String method, String path, Object body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(toJson(body)));
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new ApiResponse(response.statusCode(), response.body(), response.headers());
    }

    private Map<String, Object> parseMap(String body) throws Exception {
        return MAPPER.readValue(body, MAP_TYPE);
    }

    private List<Map<String, Object>> parseList(String body) throws Exception {
        return MAPPER.readValue(body, LIST_TYPE);
    }

    private String toJson(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + System.nanoTime() + "@example.invalid";
    }

    private record ApiResponse(int status, String body, HttpHeaders headers) {
    }

    private static final class Roles {
        private static final int USER = 2;
    }

    private static final class Permissions {
        private static final int USER = 1;
    }
}
