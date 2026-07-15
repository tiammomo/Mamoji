package com.mamoji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.repository.InMemoryStore;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    "mamoji.object-storage.enabled=false",
    "mamoji.outbox.consumer.enabled=false",
    "mamoji.notifications.reminder.enabled=false",
    "mamoji.notifications.delivery.enabled=false",
    "debug=false",
    "spring.main.log-startup-info=false",
    "logging.level.root=WARN"
})
class ConcurrentReadWriteIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    InMemoryStore coreStore;

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
    void reconciliationUsesLockedDatabaseBalanceInsteadOfStaleReadModel() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Reconciliation consistency");
        Map<String, Object> account = createAccount(token, companyId, "Authoritative balance", "1000");
        long accountId = id(account);
        jdbc.update("UPDATE accounts SET balance = '875', available_balance = '875' WHERE id = ?", accountId);

        ApiResponse response = request("POST", "/api/v1/accounts/" + accountId
            + "/reconciliations?companyId=" + companyId, Map.of(
            "statementDate", LocalDate.now().toString(),
            "statementBalance", 875
        ), token);

        assertEquals(200, response.status(), response.body());
        Map<String, Object> record = map(response.body());
        assertEquals("reconciled", record.get("status"));
        assertEquals(0, new BigDecimal("875.00").compareTo(decimal(record.get("systemBalance"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(decimal(record.get("difference"))));
    }

    @Test
    void transactionSummaryReadsDatabaseWhenCompatibilityCacheEntryIsMissing() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Database summary");
        Map<String, Object> account = createAccount(token, companyId, "Summary account", "1000");
        Map<String, Object> category = createCategory(token, companyId, "Summary expense", "expense");
        ApiResponse created = request("POST", "/api/v1/transactions", Map.of(
            "companyId", companyId,
            "type", 2,
            "amount", 42,
            "accountId", id(account),
            "categoryId", id(category),
            "date", LocalDate.now().toString(),
            "note", "database-summary-row"
        ), token);
        assertEquals(200, created.status(), created.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> transaction = (Map<String, Object>) map(created.body()).get("transaction");
        long transactionId = id(transaction);
        TransactionRecord cached = coreStore.transactions.remove(transactionId);
        try {
            ApiResponse summary = request(
                "GET",
                "/api/v1/transactions/summary?companyId=" + companyId + "&keyword=database-summary-row",
                null,
                token
            );
            assertEquals(200, summary.status(), summary.body());
            Map<String, Object> totals = map(summary.body());
            assertEquals(1, ((Number) totals.get("rows")).intValue());
            assertEquals(0, new BigDecimal("42").compareTo(decimal(totals.get("expense"))));
            assertEquals(1, ((Number) totals.get("reviewCount")).intValue());
        } finally {
            if (cached != null) {
                coreStore.transactions.put(transactionId, cached);
            }
        }
    }

    @Test
    void concurrentApprovalSubmissionCreatesOnlyOnePendingRequest() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Concurrent approval");
        ApiResponse voucherResponse = request("POST", "/api/v1/receipts", Map.of(
            "companyId", companyId,
            "title", "Concurrent reimbursement",
            "voucherType", "reimbursement",
            "direction", "expense",
            "counterparty", "Employee",
            "amount", 100
        ), token);
        assertEquals(200, voucherResponse.status(), voucherResponse.body());
        long voucherId = id(map(voucherResponse.body()));
        Map<String, Object> body = Map.of(
            "companyId", companyId,
            "requestType", "reimbursement",
            "entityType", "receipt_voucher",
            "entityId", voucherId,
            "title", "Only one pending approval",
            "amount", 100
        );
        String leaseKey = "approval:" + companyId + ":receipt_voucher:" + voucherId;

        CompletableFuture<ApiResponse> first;
        CompletableFuture<ApiResponse> second;
        try (Connection blocker = holdApprovalLease(leaseKey)) {
            first = requestAsync("POST", "/api/v1/approvals", body, token);
            second = requestAsync("POST", "/api/v1/approvals", body, token);
            awaitBlockedQueries("pg_advisory_xact_lock", 2);
            blocker.commit();
        }
        ApiResponse firstResponse = first.get(10, TimeUnit.SECONDS);
        ApiResponse secondResponse = second.get(10, TimeUnit.SECONDS);

        assertEquals(List.of(200, 409), List.of(firstResponse.status(), secondResponse.status()).stream().sorted().toList(),
            firstResponse.body() + " / " + secondResponse.body());
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM approval_requests
            WHERE company_id = ? AND entity_type = 'receipt_voucher' AND entity_id = ? AND status = 'pending'
            """, Integer.class, companyId, voucherId));
    }

    @Test
    void concurrentPayrollClosePublishesSideEffectsOnce() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Concurrent payroll");
        createEmployee(token, companyId);
        ApiResponse created = request("POST", "/api/v1/payroll-runs", Map.of(
            "companyId", companyId,
            "period", "2026-07"
        ), token);
        assertEquals(200, created.status(), created.body());
        long runId = id(map(created.body()));

        CompletableFuture<ApiResponse> first;
        CompletableFuture<ApiResponse> second;
        try (Connection blocker = lockRow("SELECT id FROM payroll_runs WHERE id = ? FOR UPDATE", runId)) {
            first = requestAsync("POST", "/api/v1/payroll-runs/" + runId + "/close", null, token);
            second = requestAsync("POST", "/api/v1/payroll-runs/" + runId + "/close", null, token);
            awaitBlockedQueries("FROM payroll_runs WHERE id", 2);
            blocker.commit();
        }
        ApiResponse firstResponse = first.get(10, TimeUnit.SECONDS);
        ApiResponse secondResponse = second.get(10, TimeUnit.SECONDS);

        assertEquals(200, firstResponse.status(), firstResponse.body());
        assertEquals(200, secondResponse.status(), secondResponse.body());
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'payroll.run.closed' AND aggregate_id = ?",
            Integer.class,
            runId
        ));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE entity_type = 'payroll_run' AND entity_id = ? AND action = 'close'",
            Integer.class,
            runId
        ));
    }

    @Test
    void concurrentRecurringExecutionPostsOneTransactionAndIncrementsOnce() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Concurrent recurring");
        Map<String, Object> account = createAccount(token, companyId, "Recurring account", "1000");
        createCategory(token, companyId, "Recurring expense", "expense");
        String note = "recurring-" + System.nanoTime();
        ApiResponse created = request("POST", "/api/v1/recurring", Map.of(
            "companyId", companyId,
            "name", "Concurrent recurring item",
            "type", 2,
            "amount", 25,
            "frequency", "monthly",
            "interval", 1,
            "startDate", LocalDate.now().toString(),
            "note", note
        ), token);
        assertEquals(200, created.status(), created.body());
        String recurringId = String.valueOf(map(created.body()).get("id"));

        CompletableFuture<ApiResponse> first;
        CompletableFuture<ApiResponse> second;
        try (Connection blocker = lockRow("SELECT id FROM recurring_items WHERE id = ? FOR UPDATE", recurringId)) {
            String path = "/api/v1/recurring/" + recurringId + "/execute?companyId=" + companyId;
            first = requestAsync("POST", path, null, token);
            second = requestAsync("POST", path, null, token);
            awaitBlockedQueries("FROM recurring_items WHERE id", 2);
            blocker.commit();
        }
        ApiResponse firstResponse = first.get(10, TimeUnit.SECONDS);
        ApiResponse secondResponse = second.get(10, TimeUnit.SECONDS);

        assertEquals(List.of(200, 409), List.of(firstResponse.status(), secondResponse.status()).stream().sorted().toList(),
            firstResponse.body() + " / " + secondResponse.body());
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM transactions WHERE company_id = ? AND account_id = ? AND note = ?",
            Integer.class,
            companyId,
            id(account),
            note
        ));
        Map<String, Object> state = jdbc.queryForMap(
            "SELECT execution_count, last_executed FROM recurring_items WHERE id = ?",
            recurringId
        );
        assertEquals(1, ((Number) state.get("execution_count")).intValue());
        assertEquals(LocalDate.now().toString(), state.get("last_executed"));
    }

    private Connection holdApprovalLease(String leaseKey) throws Exception {
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"
            )) {
                statement.setString(1, leaseKey);
                statement.executeQuery().close();
            }
            return connection;
        } catch (Exception ex) {
            connection.close();
            throw ex;
        }
    }

    private Connection lockRow(String sql, Object id) throws Exception {
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, id);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next(), "Expected a row to lock");
                }
            }
            return connection;
        } catch (Exception ex) {
            connection.close();
            throw ex;
        }
    }

    private void awaitBlockedQueries(String queryFragment, int expected) throws Exception {
        for (int attempt = 0; attempt < 250; attempt++) {
            Integer blocked = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND query LIKE ?
                """, Integer.class, "%" + queryFragment + "%");
            if (blocked != null && blocked >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for " + expected + " blocked queries containing " + queryFragment);
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

    private String adminToken() throws Exception {
        ApiResponse response = request("POST", "/api/v1/auth/login", Map.of(
            "email", "test@mamoji.com",
            "password", "123456"
        ), null);
        assertEquals(200, response.status(), response.body());
        return String.valueOf(map(response.body()).get("token"));
    }

    private long createCompany(String token, String prefix) throws Exception {
        ApiResponse response = request("POST", "/api/v1/enterprise/companies", Map.of(
            "name", prefix + " " + System.nanoTime(),
            "entityType", "company",
            "currency", "CNY",
            "industry", "integration-test",
            "taxpayerType", "test"
        ), token);
        assertEquals(200, response.status(), response.body());
        return id(map(response.body()));
    }

    private Map<String, Object> createAccount(String token, long companyId, String name, String balance) throws Exception {
        ApiResponse response = request("POST", "/api/v1/accounts", Map.of(
            "companyId", companyId,
            "name", name,
            "type", "bank",
            "balance", balance
        ), token);
        assertEquals(200, response.status(), response.body());
        return map(response.body());
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
        return map(response.body());
    }

    private void createEmployee(String token, long companyId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyId", companyId);
        body.put("name", "Payroll employee");
        body.put("email", "payroll-" + System.nanoTime() + "@example.invalid");
        body.put("position", "Engineer");
        body.put("employmentType", "full_time");
        body.put("status", "active");
        body.put("accessRole", "employee");
        body.put("accessScope", "self");
        body.put("hireDate", "2026-07-01");
        body.put("salary", 10000);
        ApiResponse response = request("POST", "/api/v1/enterprise/employees", body, token);
        assertEquals(200, response.status(), response.body());
    }

    private ApiResponse request(String method, String path, Object body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new ApiResponse(response.statusCode(), response.body());
    }

    private Map<String, Object> map(String json) throws Exception {
        return MAPPER.readValue(json, MAP_TYPE);
    }

    private long id(Map<String, Object> value) {
        return ((Number) value.get("id")).longValue();
    }

    private BigDecimal decimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private record ApiResponse(int status, String body) { }
}
