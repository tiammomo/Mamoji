package com.mamoji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "mamoji.runtime.environment=local",
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

    @LocalServerPort
    int port;

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
