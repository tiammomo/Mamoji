package com.mamoji.accountingperiod.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AccountingPeriodMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void migrationProvisionsTypedCloseControlsAndDatabaseWriteFence() throws Exception {
        migrateToThirtyTwo(POSTGRES);
        long ownerId;
        long existingCompanyId;
        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            ownerId = insertUser(statement, "period-owner@mamoji.test");
            existingCompanyId = insertCompany(statement, ownerId, "Existing period tenant");
        }

        migrateLatest(POSTGRES);

        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            assertEquals("33", latestVersion(statement));
            try (ResultSet result = statement.executeQuery("""
                SELECT version, closed_through, last_action,
                       pg_typeof(closed_through)::TEXT AS closed_type,
                       pg_typeof(last_action_at)::TEXT AS action_time_type
                FROM accounting_period_controls
                WHERE company_id = %d
                """.formatted(existingCompanyId))) {
                assertTrue(result.next());
                assertEquals(0, result.getLong("version"));
                assertEquals(null, result.getDate("closed_through"));
                assertEquals("INITIAL", result.getString("last_action"));
                assertEquals("date", result.getString("closed_type"));
                assertEquals("timestamp with time zone", result.getString("action_time_type"));
            }
            assertTrue(constraints(statement).containsAll(Set.of(
                "fk_accounting_period_controls_company",
                "fk_accounting_period_controls_actor",
                "ck_accounting_period_controls_version",
                "ck_accounting_period_controls_month_end",
                "ck_accounting_period_controls_action_details",
                "ck_accounting_period_controls_lifecycle"
            )));
            assertTrue(triggerExists(statement, "trg_company_accounting_period_control", "companies"));
            assertTrue(triggerExists(statement, "trg_transactions_open_accounting_period", "transactions"));

            long newCompanyId = insertCompany(statement, ownerId, "New period tenant");
            assertEquals(1, countControl(statement, newCompanyId));

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE accounting_period_controls
                SET closed_through = DATE '2026-08-15'
                WHERE company_id = %d
                """.formatted(existingCompanyId)));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE accounting_period_controls
                SET last_action = 'REOPEN', last_action_by = %d, last_action_reason = 'x'
                WHERE company_id = %d
                """.formatted(ownerId, existingCompanyId)));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE accounting_period_controls
                SET last_action = 'REOPEN', last_action_by = %d, last_action_reason = NULL
                WHERE company_id = %d
                """.formatted(ownerId, existingCompanyId)));
        }
    }

    private static long insertUser(Statement statement, String email) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO users (
                email, nickname, avatar, family_id, role, permissions,
                password_hash, created_at, updated_at
            ) VALUES (
                '%s', 'Period migration user', '', NULL, 1, 15,
                'not-used', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            ) RETURNING id
            """.formatted(email))) {
            result.next();
            return result.getLong(1);
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
            return result.getLong(1);
        }
    }

    private static Set<String> constraints(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT conname FROM pg_constraint
            WHERE conrelid = 'accounting_period_controls'::regclass AND convalidated
            """)) {
            Set<String> names = new HashSet<>();
            while (result.next()) names.add(result.getString(1));
            return names;
        }
    }

    private static boolean triggerExists(Statement statement, String triggerName, String tableName)
        throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT EXISTS(
                SELECT 1 FROM pg_trigger
                WHERE tgname = '%s' AND tgrelid = '%s'::regclass AND NOT tgisinternal
            )
            """.formatted(triggerName, tableName))) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private static int countControl(Statement statement, long companyId) throws SQLException {
        try (ResultSet result = statement.executeQuery(
            "SELECT COUNT(*) FROM accounting_period_controls WHERE company_id = " + companyId
        )) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String latestVersion(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT version FROM flyway_schema_history
            WHERE success = true ORDER BY installed_rank DESC LIMIT 1
            """)) {
            result.next();
            return result.getString(1);
        }
    }

    private static Connection connection(PostgreSQLContainer<?> database) throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private static void migrateToThirtyTwo(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .target("32")
            .load()
            .migrate();
    }

    private static void migrateLatest(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .load()
            .migrate();
    }
}
