package com.mamoji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.TransactionImportService;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "mamoji.runtime.environment=local",
    "mamoji.bootstrap.mode=demo",
    "mamoji.product.modules.people-core-enabled=true",
    "mamoji.product.modules.workforce-cost-enabled=true",
    "mamoji.product.modules.talent-suite-enabled=true",
    "mamoji.product.modules.tax-workspace-enabled=true",
    "mamoji.product.modules.backup-ui-enabled=true",
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
abstract class AbstractPostgresIntegrationTest {
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

    @Autowired
    TransactionImportService transactionImportService;

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    protected long createCompany(String token, String name) throws Exception {
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

    protected Map<String, Object> createAccount(String token, long companyId, String name, String balance) throws Exception {
        ApiResponse response = request("POST", "/api/v1/accounts", Map.of(
            "companyId", companyId,
            "name", name,
            "type", "bank",
            "balance", balance
        ), token);
        assertEquals(200, response.status(), response.body());
        return parseMap(response.body());
    }

    protected Map<String, Object> createCategory(String token, long companyId, String name, String type) throws Exception {
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

    protected Map<String, Object> createEmployee(
        String token,
        long companyId,
        long userId,
        String email,
        String accessRole
    ) throws Exception {
        return createEmployee(token, companyId, userId, email, accessRole, "company", null);
    }

    protected Map<String, Object> createEmployee(
        String token,
        long companyId,
        long userId,
        String email,
        String accessRole,
        String accessScope
    ) throws Exception {
        return createEmployee(token, companyId, userId, email, accessRole, accessScope, null);
    }

    protected Map<String, Object> createEmployee(
        String token,
        long companyId,
        long userId,
        String email,
        String accessRole,
        String accessScope,
        Long departmentId
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
        if (departmentId != null) body.put("departmentId", departmentId);
        ApiResponse response = request("POST", "/api/v1/enterprise/employees", body, token);
        assertEquals(200, response.status(), response.body());
        return parseMap(response.body());
    }

    protected long createDepartment(String token, long companyId, String name) throws Exception {
        ApiResponse response = request("POST", "/api/v1/enterprise/departments", Map.of(
            "companyId", companyId,
            "name", name,
            "costCenter", "TEST",
            "budget", 0
        ), token);
        assertEquals(200, response.status(), response.body());
        return ((Number) parseMap(response.body()).get("id")).longValue();
    }

    protected Connection lockRow(String sql, Object id) throws Exception {
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, id);
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

    protected void awaitBlockedQuery(String table) throws Exception {
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

    protected void awaitBlockedQueries(String queryFragment, int expected) throws Exception {
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
        throw new AssertionError("Timed out waiting for " + expected
            + " blocked queries containing " + queryFragment);
    }

    protected CompletableFuture<ApiResponse> requestAsync(String method, String path, Object body, String token) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return request(method, path, body, token);
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        });
    }

    protected Map<String, Object> createTransaction(
        String token,
        long companyId,
        Map<String, Object> account,
        Map<String, Object> category,
        String amount
    ) throws Exception {
        return createTransaction(token, companyId, account, category, amount, "2026-07-14");
    }

    protected Map<String, Object> createTransaction(
        String token,
        long companyId,
        Map<String, Object> account,
        Map<String, Object> category,
        String amount,
        String date
    ) throws Exception {
        ApiResponse response = request("POST", "/api/v1/transactions", Map.of(
            "companyId", companyId,
            "type", 2,
            "amount", amount,
            "accountId", account.get("id"),
            "categoryId", category.get("id"),
            "date", date,
            "note", "company scope test"
        ), token);
        assertEquals(200, response.status(), response.body());
        return parseMap(response.body());
    }

    protected long transactionCount(String token, long companyId) throws Exception {
        ApiResponse response = request("GET", "/api/v1/transactions?companyId=" + companyId + "&size=200", null, token);
        assertEquals(200, response.status(), response.body());
        return ((Number) parseMap(response.body()).get("totalElements")).longValue();
    }

    protected void assertAccountBalances(
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

    protected BigDecimal budgetSpent(String token, long companyId) throws Exception {
        ApiResponse response = request("GET", "/api/v1/budgets?companyId=" + companyId + "&size=200", null, token);
        assertEquals(200, response.status(), response.body());
        Object content = parseMap(response.body()).get("content");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> budgets = (List<Map<String, Object>>) content;
        assertEquals(1, budgets.size());
        return decimal(budgets.get(0).get("spent"));
    }

    protected BigDecimal decimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    protected Map<String, Object> login(String email, String password) throws Exception {
        ApiResponse response = request("POST", "/api/v1/auth/login", Map.of(
            "email", email,
            "password", password
        ), null);
        assertEquals(200, response.status(), response.body());
        return parseMap(response.body());
    }

    protected String registerInvitedUser(String email) throws Exception {
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

    protected String createInvite(String email, int permissions) throws Exception {
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

    protected ApiResponse request(String method, String path, Object body, String token) throws Exception {
        return request(method, path, body, token, Map.of());
    }

    protected ApiResponse request(
        String method,
        String path,
        Object body,
        String token,
        Map<String, String> headers
    ) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10));
        headers.forEach(builder::header);
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

    protected Map<String, Object> parseMap(String body) throws Exception {
        return MAPPER.readValue(body, MAP_TYPE);
    }

    protected List<Map<String, Object>> parseList(String body) throws Exception {
        return MAPPER.readValue(body, LIST_TYPE);
    }

    protected String toJson(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }

    protected String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    protected String uniqueEmail(String prefix) {
        return prefix + "-" + System.nanoTime() + "@example.invalid";
    }

    protected String adminToken() throws Exception {
        return text(login("test@mamoji.com", "123456").get("token"));
    }

    protected long id(Map<String, Object> value) {
        return ((Number) value.get("id")).longValue();
    }

    protected record ApiResponse(int status, String body, HttpHeaders headers) {
    }

    protected static final class Roles {
        protected static final int USER = 2;
    }

    protected static final class Permissions {
        protected static final int USER = 1;
    }
}
