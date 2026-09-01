package com.mamoji.platform.identity.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mamoji.platform.identity.invitation.domain.InvitationTokenDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PlatformIdentityMigrationTest {
    private static final String VALID_DIGEST = "sha256:" + "a".repeat(43);
    private static final String LEGACY_INVITATION_TOKEN = "1".repeat(64);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void identityMigrationsHardenLegacyAccountsSessionsAndInvitations() throws Exception {
        migrateToVersionEleven();

        try (Connection connection = connection()) {
            long userId = insertUser(connection);
            insertSession(connection, VALID_DIGEST, userId, "2026-09-01T10:00:00Z", "2026-09-01T11:00:00Z");
            insertSession(connection, "r".repeat(43), userId, "2026-09-01T10:00:00Z", "2026-09-01T11:00:00Z");
            insertSession(connection, "sha256:" + "b".repeat(43), userId, "not-a-time", "2026-09-01T11:00:00Z");
            insertSession(connection, "sha256:" + "c".repeat(43), userId, "2026-09-01T12:00:00Z", "2026-09-01T11:00:00Z");
            insertSession(connection, "sha256:" + "d".repeat(43), userId + 9999, "2026-09-01T10:00:00Z", "2026-09-01T11:00:00Z");
            insertInvitation(
                connection,
                LEGACY_INVITATION_TOKEN,
                userId,
                "migration-invite@mamoji.test",
                "2026-09-01T10:00:00Z",
                "2026-09-02T10:00:00Z"
            );
            insertInvitation(
                connection,
                "2".repeat(64),
                userId,
                "invalid-time@mamoji.test",
                "not-a-time",
                "2026-09-02T10:00:00Z"
            );
            insertInvitation(
                connection,
                "3".repeat(64),
                userId + 9999,
                "orphan-inviter@mamoji.test",
                "2026-09-01T10:00:00Z",
                "2026-09-02T10:00:00Z"
            );
        }

        migrateLatest();

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet sessions = statement.executeQuery("SELECT token FROM auth_tokens")) {
                sessions.next();
                assertEquals(VALID_DIGEST, sessions.getString("token"));
                assertFalse(sessions.next());
            }
            try (ResultSet invitations = statement.executeQuery("""
                SELECT token, email, invited_by_user_id, pg_typeof(expires_at)::TEXT AS expiry_type
                FROM registration_invites
                ORDER BY email
                """)) {
                invitations.next();
                assertEquals(
                    InvitationTokenDigest.fromRawToken(LEGACY_INVITATION_TOKEN).value(),
                    invitations.getString("token")
                );
                assertEquals("timestamp with time zone", invitations.getString("expiry_type"));
                invitations.next();
                assertEquals("orphan-inviter@mamoji.test", invitations.getString("email"));
                assertEquals(
                    InvitationTokenDigest.fromRawToken("3".repeat(64)).value(),
                    invitations.getString("token")
                );
                assertNull(invitations.getObject("invited_by_user_id"));
                assertFalse(invitations.next());
            }
            try (ResultSet users = statement.executeQuery("""
                SELECT email,
                       pg_typeof(created_at)::TEXT AS created_type,
                       pg_typeof(updated_at)::TEXT AS updated_type
                FROM users
                """)) {
                users.next();
                assertEquals("migration@mamoji.test", users.getString("email"));
                assertEquals("timestamp with time zone", users.getString("created_type"));
                assertEquals("timestamp with time zone", users.getString("updated_type"));
                assertFalse(users.next());
            }
            try (ResultSet version = statement.executeQuery("""
                SELECT version FROM flyway_schema_history
                WHERE success = true ORDER BY installed_rank DESC LIMIT 1
                """)) {
                version.next();
                assertEquals("19", version.getString("version"));
            }
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO auth_tokens (token, user_id, created_at, expires_at)
                SELECT 'plaintext', id, NOW(), NOW() + INTERVAL '1 hour' FROM users LIMIT 1
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO registration_invites (
                    token, email, role, permissions, expires_at, invited_by_user_id,
                    created_at, updated_at
                ) SELECT 'plaintext', 'new@mamoji.test', 2, 15, NOW() + INTERVAL '1 day', id, NOW(), NOW()
                  FROM users LIMIT 1
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO users (
                    email, nickname, avatar, family_id, role, permissions,
                    password_hash, created_at, updated_at
                ) VALUES (
                    'Not-Normalized@Mamoji.Test', 'Invalid', '', NULL, 2, 15,
                    'not-used', NOW(), NOW()
                )
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE users SET permissions = 16"
            ));
        }
    }

    @Test
    void userAccountMigrationRejectsDuplicateNormalizedEmailsWithoutPartialUpgrade() throws Exception {
        try (PostgreSQLContainer<?> dirtyDatabase = new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            dirtyDatabase.start();
            Flyway.configure()
                .dataSource(
                    dirtyDatabase.getJdbcUrl(),
                    dirtyDatabase.getUsername(),
                    dirtyDatabase.getPassword()
                )
                .target("13")
                .load()
                .migrate();
            try (
                Connection connection = DriverManager.getConnection(
                    dirtyDatabase.getJdbcUrl(),
                    dirtyDatabase.getUsername(),
                    dirtyDatabase.getPassword()
                );
                Statement statement = connection.createStatement()
            ) {
                statement.executeUpdate("""
                    INSERT INTO users (
                        email, nickname, avatar, family_id, role, permissions,
                        password_hash, created_at, updated_at
                    ) VALUES
                        ('duplicate@mamoji.test', 'First', '', NULL, 2, 15,
                         'not-used', '2026-09-01T10:00:00Z', '2026-09-01T10:00:00Z'),
                        (' Duplicate@Mamoji.Test ', 'Second', '', NULL, 2, 15,
                         'not-used', '2026-09-01T10:00:00Z', '2026-09-01T10:00:00Z')
                    """);
            }

            Flyway latest = Flyway.configure()
                .dataSource(
                    dirtyDatabase.getJdbcUrl(),
                    dirtyDatabase.getUsername(),
                    dirtyDatabase.getPassword()
                )
                .load();
            FlywayException failure = assertThrows(FlywayException.class, latest::migrate);
            assertTrue(containsMessage(failure, "duplicate normalized email addresses"));

            try (
                Connection connection = DriverManager.getConnection(
                    dirtyDatabase.getJdbcUrl(),
                    dirtyDatabase.getUsername(),
                    dirtyDatabase.getPassword()
                );
                Statement statement = connection.createStatement()
            ) {
                try (ResultSet version = statement.executeQuery("""
                    SELECT version FROM flyway_schema_history
                    WHERE success = true ORDER BY installed_rank DESC LIMIT 1
                    """)) {
                    version.next();
                    assertEquals("13", version.getString("version"));
                }
                try (ResultSet type = statement.executeQuery("""
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'users'
                      AND column_name = 'created_at'
                    """)) {
                    type.next();
                    assertEquals("text", type.getString("data_type"));
                }
            }
        }
    }

    private boolean containsMessage(Throwable failure, String expected) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void migrateToVersionEleven() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .target("11")
            .load()
            .migrate();
    }

    private void migrateLatest() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
    }

    private long insertUser(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO users (
                email, nickname, avatar, family_id, role, permissions,
                password_hash, created_at, updated_at
            ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?)
            RETURNING id
            """)) {
            statement.setString(1, "  Migration@Mamoji.Test  ");
            statement.setString(2, "Migration User");
            statement.setString(3, "");
            statement.setInt(4, 1);
            statement.setInt(5, 15);
            statement.setString(6, "not-used");
            statement.setString(7, "2026-09-01T10:00:00Z");
            statement.setString(8, "2026-09-01T10:00:00Z");
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong("id");
            }
        }
    }

    private void insertSession(
        Connection connection,
        String token,
        long userId,
        String createdAt,
        String expiresAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO auth_tokens (token, user_id, created_at, expires_at)
            VALUES (?, ?, ?, ?)
            """)) {
            statement.setString(1, token);
            statement.setLong(2, userId);
            statement.setString(3, createdAt);
            statement.setString(4, expiresAt);
            statement.executeUpdate();
        }
    }

    private void insertInvitation(
        Connection connection,
        String token,
        long invitedByUserId,
        String email,
        String createdAt,
        String expiresAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO registration_invites (
                token, email, role, permissions, expires_at, accepted_at,
                accepted_user_id, invited_by_user_id, created_at, updated_at
            ) VALUES (?, ?, 2, 15, ?, NULL, NULL, ?, ?, ?)
            """)) {
            statement.setString(1, token);
            statement.setString(2, email);
            statement.setString(3, expiresAt);
            statement.setLong(4, invitedByUserId);
            statement.setString(5, createdAt);
            statement.setString(6, createdAt);
            statement.executeUpdate();
        }
    }
}
