package com.mamoji.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class CompanyWorkspaceMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void migrationProvisionsOnlyMissingCompanyWorkspaceRecords() throws Exception {
        migrateToTwentySeven();
        long emptyOwnerId;
        long customizedOwnerId;
        long ledgerOwnerId;
        long emptyCompanyId;
        long customizedCompanyId;
        long existingLedgerId;
        long existingExpenseId;
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            emptyOwnerId = insertUser(statement, "empty-workspace@mamoji.test", "Empty owner");
            customizedOwnerId = insertUser(statement, "custom-workspace@mamoji.test", "Custom owner");
            ledgerOwnerId = insertUser(statement, "ledger-owner@mamoji.test", "Ledger owner");
            emptyCompanyId = insertCompany(statement, emptyOwnerId, "企".repeat(200));
            customizedCompanyId = insertCompany(statement, customizedOwnerId, "Customized company");
            insertMembership(statement, customizedCompanyId, customizedOwnerId, "viewer", "readonly", "inactive");
            insertMembership(statement, customizedCompanyId, ledgerOwnerId, "finance_admin", "company", "active");
            existingLedgerId = insertLedger(statement, customizedCompanyId, ledgerOwnerId);
            insertLedgerMember(statement, customizedCompanyId, existingLedgerId, ledgerOwnerId);
            existingExpenseId = insertExpenseCategory(statement, customizedCompanyId, customizedOwnerId);
        }

        migrateLatest();

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertEquals("29", latestVersion(statement));
            assertMembership(statement, emptyCompanyId, emptyOwnerId);
            assertMembership(statement, customizedCompanyId, customizedOwnerId);

            try (ResultSet result = statement.executeQuery("""
                SELECT ledger.id, ledger.name, ledger.is_default, member.role
                FROM ledgers ledger
                JOIN ledger_members member
                  ON member.ledger_id = ledger.id AND member.user_id = %d
                WHERE ledger.company_id = %d
                """.formatted(emptyOwnerId, emptyCompanyId))) {
                assertTrue(result.next());
                assertEquals(120, result.getString("name").length());
                assertTrue(result.getBoolean("is_default"));
                assertEquals("owner", result.getString("role"));
                assertFalse(result.next());
            }
            assertEquals(Set.of("income", "expense"), categoryTypes(statement, emptyCompanyId, emptyOwnerId));
            assertEquals(2, scalar(statement, "SELECT COUNT(*) FROM categories WHERE company_id = " + emptyCompanyId));

            assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM ledgers WHERE company_id = " + customizedCompanyId));
            assertEquals(existingLedgerId, scalarLong(
                statement,
                "SELECT id FROM ledgers WHERE company_id = " + customizedCompanyId
            ));
            assertFalse(booleanValue(statement, "SELECT is_default FROM ledgers WHERE id = " + existingLedgerId));
            assertEquals("admin", text(statement, """
                SELECT role FROM ledger_members
                WHERE ledger_id = %d AND user_id = %d
                """.formatted(existingLedgerId, customizedOwnerId)));
            assertEquals(2, scalar(
                statement,
                "SELECT COUNT(*) FROM ledger_members WHERE ledger_id = " + existingLedgerId
            ));
            assertEquals(Set.of("income", "expense"), categoryTypes(statement, customizedCompanyId, customizedOwnerId));
            assertEquals("Custom expense", text(
                statement,
                "SELECT name FROM categories WHERE id = " + existingExpenseId
            ));
            assertEquals(1, scalar(statement, """
                SELECT COUNT(*) FROM categories
                WHERE company_id = %d AND user_id = %d AND type = 'expense'
                """.formatted(customizedCompanyId, customizedOwnerId)));
        }
    }

    private static void assertMembership(Statement statement, long companyId, long ownerId) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT role, scope, status
            FROM company_memberships
            WHERE company_id = %d AND user_id = %d
            """.formatted(companyId, ownerId))) {
            assertTrue(result.next());
            assertEquals("founder", result.getString("role"));
            assertEquals("company", result.getString("scope"));
            assertEquals("active", result.getString("status"));
            assertFalse(result.next());
        }
    }

    private static Set<String> categoryTypes(Statement statement, long companyId, long ownerId) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT type FROM categories
            WHERE company_id = %d AND user_id = %d
            """.formatted(companyId, ownerId))) {
            Set<String> types = new java.util.HashSet<>();
            while (result.next()) types.add(result.getString("type"));
            return types;
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
                name, entity_type, credit_code, industry, taxpayer_type, currency, country,
                province, city, district, operating_region, policy_profile_key,
                fiscal_year_start_month, owner_id, created_at, updated_at
            ) VALUES (
                '%s', 'company', NULL, 'test', 'test', 'CNY', 'China',
                '', '', '', 'China', 'TEST-POLICY', 1, %d,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            ) RETURNING id
            """.formatted(name, ownerId))) {
            result.next();
            return result.getLong("id");
        }
    }

    private static void insertMembership(
        Statement statement,
        long companyId,
        long ownerId,
        String role,
        String scope,
        String status
    ) throws SQLException {
        statement.executeUpdate("""
            INSERT INTO company_memberships (
                company_id, user_id, role, scope, status, created_at, updated_at
            ) VALUES (
                %d, %d, '%s', '%s', '%s', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """.formatted(companyId, ownerId, role, scope, status));
    }

    private static long insertLedger(Statement statement, long companyId, long ownerId) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO ledgers (
                name, description, currency, owner_id, is_default, status,
                created_at, updated_at, company_id
            ) VALUES (
                'Custom ledger', 'Existing workspace', 'CNY', %d, FALSE, 1,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, %d
            ) RETURNING id
            """.formatted(ownerId, companyId))) {
            result.next();
            return result.getLong("id");
        }
    }

    private static void insertLedgerMember(
        Statement statement,
        long companyId,
        long ledgerId,
        long ownerId
    ) throws SQLException {
        statement.executeUpdate("""
            INSERT INTO ledger_members (
                company_id, ledger_id, user_id, role, nickname, avatar, joined_at
            ) VALUES (
                %d, %d, %d, 'owner', 'Custom owner', '', CURRENT_TIMESTAMP
            )
            """.formatted(companyId, ledgerId, ownerId));
    }

    private static long insertExpenseCategory(Statement statement, long companyId, long ownerId)
        throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO categories (
                name, icon, color, type, user_id, status, created_at, updated_at, company_id
            ) VALUES (
                'Custom expense', 'C', '#123456', 'expense', %d, 1,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, %d
            ) RETURNING id
            """.formatted(ownerId, companyId))) {
            result.next();
            return result.getLong("id");
        }
    }

    private static int scalar(Statement statement, String sql) throws SQLException {
        return (int) scalarLong(statement, sql);
    }

    private static long scalarLong(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static boolean booleanValue(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private static String text(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static String latestVersion(Statement statement) throws SQLException {
        return text(statement, """
            SELECT version FROM flyway_schema_history
            WHERE success = true ORDER BY installed_rank DESC LIMIT 1
            """);
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void migrateToTwentySeven() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .target("27")
            .load()
            .migrate();
    }

    private static void migrateLatest() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
    }
}
