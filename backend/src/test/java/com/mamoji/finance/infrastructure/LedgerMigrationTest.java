package com.mamoji.finance.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class LedgerMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void migrationScopesAndTypesLedgersAndEnforcesMembershipIntegrity() throws Exception {
        migrateToEighteen(POSTGRES);
        long ownerId;
        long memberId;
        long companyId;
        long otherCompanyId;
        long ledgerId;
        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            ownerId = insertUser(statement, "ledger-owner@mamoji.test", "Ledger owner");
            memberId = insertUser(statement, "ledger-member@mamoji.test", "Ledger member");
            companyId = insertCompany(statement, ownerId, "Ledger migration company");
            otherCompanyId = insertCompany(statement, ownerId, "Other ledger company");
            insertMembership(statement, companyId, ownerId, "founder");
            insertMembership(statement, companyId, memberId, "finance_admin");
            insertMembership(statement, otherCompanyId, ownerId, "founder");
            ledgerId = insertLedger(statement, ownerId, companyId, "  Migration ledger  ", true);
            long otherLedgerId = insertLedger(statement, ownerId, otherCompanyId, "Other ledger", true);
            insertLedgerMember(statement, ledgerId, ownerId, " OWNER ", " Ledger owner ");
            insertLedgerMember(statement, ledgerId, memberId, " EDITOR ", " Ledger member ");
            insertLedgerMember(statement, otherLedgerId, ownerId, "owner", "Ledger owner");
        }

        migrateLatest(POSTGRES);

        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                SELECT ledger.name, ledger.currency, ledger.is_default, ledger.created_at,
                       member.company_id, member.role, member.nickname, member.joined_at,
                       pg_typeof(ledger.is_default)::TEXT AS default_type,
                       pg_typeof(ledger.created_at)::TEXT AS created_type,
                       pg_typeof(member.joined_at)::TEXT AS joined_type
                FROM ledgers ledger
                JOIN ledger_members member
                  ON member.ledger_id = ledger.id AND member.user_id = %d
                WHERE ledger.id = %d
                """.formatted(memberId, ledgerId))) {
                result.next();
                assertEquals("Migration ledger", result.getString("name"));
                assertEquals("CNY", result.getString("currency"));
                assertTrue(result.getBoolean("is_default"));
                assertEquals(companyId, result.getLong("company_id"));
                assertEquals("editor", result.getString("role"));
                assertEquals("Ledger member", result.getString("nickname"));
                assertEquals("boolean", result.getString("default_type"));
                assertEquals("timestamp with time zone", result.getString("created_type"));
                assertEquals("timestamp with time zone", result.getString("joined_type"));
                assertFalse(result.next());
            }
            try (ResultSet version = statement.executeQuery("""
                SELECT version FROM flyway_schema_history
                WHERE success = true ORDER BY installed_rank DESC LIMIT 1
                """)) {
                version.next();
                assertEquals("22", version.getString("version"));
            }
            assertEquals(Set.of(
                "fk_ledgers_company",
                "fk_ledgers_owner",
                "fk_ledgers_company_owner",
                "ck_ledgers_company_positive",
                "ck_ledgers_name",
                "ck_ledgers_currency",
                "ck_ledgers_status",
                "ck_ledgers_lifecycle"
            ), constraints(statement, "ledgers", "fk_ledgers_", "ck_ledgers_"));
            assertEquals(Set.of(
                "uq_ledger_members_ledger_user",
                "fk_ledger_members_company",
                "fk_ledger_members_ledger",
                "fk_ledger_members_user",
                "fk_ledger_members_company_ledger",
                "fk_ledger_members_company_user",
                "ck_ledger_members_company_positive",
                "ck_ledger_members_role"
            ), constraints(statement, "ledger_members", "uq_ledger_members_", "fk_ledger_members_", "ck_ledger_members_"));
            assertEquals(1, scalar(statement, """
                SELECT COUNT(*) FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename = 'ledgers'
                  AND indexname = 'uq_ledgers_company_default'
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO ledgers (
                    name, description, currency, owner_id, is_default, status,
                    created_at, updated_at, company_id
                ) VALUES (
                    'Second default', '', 'CNY', %d, TRUE, 1,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, %d
                )
                """.formatted(ownerId, companyId)));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE ledger_members SET role = 'owner' WHERE ledger_id = " + ledgerId + " AND user_id = " + memberId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE ledgers SET owner_id = " + memberId + " WHERE id = " + ledgerId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM ledger_members WHERE ledger_id = " + ledgerId + " AND user_id = " + ownerId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE ledger_members SET company_id = " + otherCompanyId
                    + " WHERE ledger_id = " + ledgerId + " AND user_id = " + memberId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE ledgers SET currency = 'cny' WHERE id = " + ledgerId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM company_memberships WHERE company_id = " + companyId + " AND user_id = " + memberId
            ));
        }
    }

    @Test
    void migrationRejectsUnscopedLedgersWithoutPartialUpgrade() throws Exception {
        try (PostgreSQLContainer<?> dirtyDatabase = new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            dirtyDatabase.start();
            migrateToEighteen(dirtyDatabase);
            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                long ownerId = insertUser(statement, "unscoped-ledger@mamoji.test", "Unscoped owner");
                long ledgerId = insertLedger(statement, ownerId, null, "Unscoped ledger", true);
                insertLedgerMember(statement, ledgerId, ownerId, "owner", "Unscoped owner");
            }

            FlywayException failure = assertThrows(FlywayException.class, () -> migrateLatest(dirtyDatabase));
            assertTrue(containsMessage(failure, "unscoped or orphaned company owner"));

            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                try (ResultSet version = statement.executeQuery("""
                    SELECT version FROM flyway_schema_history
                    WHERE success = true ORDER BY installed_rank DESC LIMIT 1
                    """)) {
                    version.next();
                    assertEquals("18", version.getString("version"));
                }
                try (ResultSet type = statement.executeQuery("""
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'ledgers'
                      AND column_name = 'is_default'
                    """)) {
                    type.next();
                    assertEquals("integer", type.getString("data_type"));
                }
                assertEquals(0, scalar(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'ledger_members'
                      AND column_name = 'company_id'
                    """));
            }
        }
    }

    private static Set<String> constraints(Statement statement, String table, String... prefixes)
        throws SQLException {
        String predicate = java.util.Arrays.stream(prefixes)
            .map(prefix -> "conname LIKE '" + prefix + "%'")
            .collect(java.util.stream.Collectors.joining(" OR "));
        try (ResultSet result = statement.executeQuery("""
            SELECT conname
            FROM pg_constraint
            WHERE conrelid = '%s'::regclass
              AND convalidated
              AND (%s)
            """.formatted(table, predicate))) {
            Set<String> names = new HashSet<>();
            while (result.next()) names.add(result.getString("conname"));
            return names;
        }
    }

    private static int scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static long insertUser(Statement statement, String email, String nickname) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO users (
                email, nickname, avatar, family_id, role, permissions,
                password_hash, created_at, updated_at
            ) VALUES (
                '%s', '%s', '', NULL, 1, 15,
                'not-used', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            ) RETURNING id
            """.formatted(email, nickname))) {
            result.next();
            return result.getLong("id");
        }
    }

    private static long insertCompany(Statement statement, long ownerId, String name) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO companies (
                name, entity_type, industry, taxpayer_type, currency, owner_id, created_at, updated_at
            ) VALUES (
                '%s', 'company', 'test', 'test', 'CNY', %d, '2026-09-01', '2026-09-01'
            ) RETURNING id
            """.formatted(name, ownerId))) {
            result.next();
            return result.getLong("id");
        }
    }

    private static void insertMembership(Statement statement, long companyId, long userId, String role)
        throws SQLException {
        statement.executeUpdate("""
            INSERT INTO company_memberships (
                company_id, user_id, role, scope, status, created_at, updated_at
            ) VALUES (
                %d, %d, '%s', 'company', 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """.formatted(companyId, userId, role));
    }

    private static long insertLedger(
        Statement statement,
        long ownerId,
        Long companyId,
        String name,
        boolean isDefault
    ) throws SQLException {
        String companyValue = companyId == null ? "NULL" : String.valueOf(companyId);
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO ledgers (
                name, description, currency, owner_id, is_default, status,
                created_at, updated_at, company_id
            ) VALUES (
                '%s', ' Migration fixture ', ' cny ', %d, %d, 1,
                ' 2026-09-01T09:00:00Z ', ' 2026-09-01T10:00:00Z ', %s
            ) RETURNING id
            """.formatted(name, ownerId, isDefault ? 1 : 0, companyValue))) {
            result.next();
            return result.getLong("id");
        }
    }

    private static void insertLedgerMember(
        Statement statement,
        long ledgerId,
        long userId,
        String role,
        String nickname
    ) throws SQLException {
        statement.executeUpdate("""
            INSERT INTO ledger_members (ledger_id, user_id, role, nickname, avatar, joined_at)
            VALUES (%d, %d, '%s', '%s', ' avatar ', ' 2026-09-01T09:30:00Z ')
            """.formatted(ledgerId, userId, role, nickname));
    }

    private static Connection connection(PostgreSQLContainer<?> database) throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private static void migrateToEighteen(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .target(MigrationVersion.fromVersion("18"))
            .load()
            .migrate();
    }

    private static void migrateLatest(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .load()
            .migrate();
    }

    private static boolean containsMessage(Throwable failure, String expected) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) return true;
            current = current.getCause();
        }
        return false;
    }
}
