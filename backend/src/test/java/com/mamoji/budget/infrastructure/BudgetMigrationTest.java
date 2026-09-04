package com.mamoji.budget.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class BudgetMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void migrationConvertsLegacyBudgetsAndEnforcesOwnershipAndValueConstraints() throws Exception {
        migrateToFifteen(POSTGRES);
        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            long userId = insertUser(statement, "budget-migration@mamoji.test");
            long companyId = insertCompany(statement, userId);
            insertBudget(statement, userId, companyId);
        }

        migrateLatest(POSTGRES);

        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                SELECT name, amount, start_date, warning_reached, created_at,
                       pg_typeof(amount)::TEXT AS amount_type,
                       pg_typeof(start_date)::TEXT AS start_type,
                       pg_typeof(warning_reached)::TEXT AS warning_type,
                       pg_typeof(created_at)::TEXT AS created_type
                FROM budgets
                """)) {
                result.next();
                assertEquals("Operations budget", result.getString("name"));
                assertEquals("1000.50", result.getBigDecimal("amount").toPlainString());
                assertEquals("2026-09-01", result.getObject("start_date").toString());
                assertFalse(result.getBoolean("warning_reached"));
                assertEquals("numeric", result.getString("amount_type"));
                assertEquals("date", result.getString("start_type"));
                assertEquals("boolean", result.getString("warning_type"));
                assertEquals("timestamp with time zone", result.getString("created_type"));
                assertFalse(result.next());
            }
            try (ResultSet version = statement.executeQuery("""
                SELECT version FROM flyway_schema_history
                WHERE success = true ORDER BY installed_rank DESC LIMIT 1
                """)) {
                version.next();
                assertEquals("20", version.getString("version"));
            }
            try (ResultSet constraints = statement.executeQuery("""
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'budgets'::regclass
                  AND conname IN (
                    'fk_budgets_company', 'fk_budgets_ledger', 'fk_budgets_category',
                    'fk_budgets_user', 'ck_budgets_amount', 'ck_budgets_dates',
                    'ck_budgets_projection', 'ck_budgets_lifecycle'
                  )
                  AND convalidated
                """)) {
                Set<String> names = new java.util.HashSet<>();
                while (constraints.next()) {
                    names.add(constraints.getString("conname"));
                }
                assertEquals(Set.of(
                    "fk_budgets_company",
                    "fk_budgets_ledger",
                    "fk_budgets_category",
                    "fk_budgets_user",
                    "ck_budgets_amount",
                    "ck_budgets_dates",
                    "ck_budgets_projection",
                    "ck_budgets_lifecycle"
                ), names);
            }
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE budgets SET amount = 0"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE budgets SET spent = -1"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE budgets SET end_date = start_date - 1"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE budgets SET company_id = NULL"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                DELETE FROM users WHERE email = 'budget-migration@mamoji.test'
                """));
        }
    }

    @Test
    void migrationRejectsUnscopedBudgetsWithoutPartialUpgrade() throws Exception {
        try (PostgreSQLContainer<?> dirtyDatabase = new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            dirtyDatabase.start();
            migrateToFifteen(dirtyDatabase);
            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                long userId = insertUser(statement, "dirty-budget@mamoji.test");
                insertBudget(statement, userId, null);
            }

            Flyway latest = Flyway.configure()
                .dataSource(dirtyDatabase.getJdbcUrl(), dirtyDatabase.getUsername(), dirtyDatabase.getPassword())
                .load();
            FlywayException failure = assertThrows(FlywayException.class, latest::migrate);
            assertTrue(containsMessage(failure, "unscoped or orphaned owner reference"));

            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                try (ResultSet version = statement.executeQuery("""
                    SELECT version FROM flyway_schema_history
                    WHERE success = true ORDER BY installed_rank DESC LIMIT 1
                    """)) {
                    version.next();
                    assertEquals("15", version.getString("version"));
                }
                try (ResultSet type = statement.executeQuery("""
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'budgets'
                      AND column_name = 'amount'
                    """)) {
                    type.next();
                    assertEquals("text", type.getString("data_type"));
                }
            }
        }
    }

    private static long insertUser(Statement statement, String email) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO users (
                email, nickname, avatar, family_id, role, permissions,
                password_hash, created_at, updated_at
            ) VALUES (
                '%s', 'Migration owner', '', NULL, 1, 15,
                'not-used', NOW(), NOW()
            ) RETURNING id
            """.formatted(email))) {
            result.next();
            return result.getLong("id");
        }
    }

    private static long insertCompany(Statement statement, long userId) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO companies (
                name, entity_type, industry, taxpayer_type, currency, owner_id, created_at, updated_at
            ) VALUES (
                'Migration Company', 'company', 'test', 'test', 'CNY', %d, '2026-09-01', '2026-09-01'
            ) RETURNING id
            """.formatted(userId))) {
            result.next();
            return result.getLong("id");
        }
    }

    private static void insertBudget(Statement statement, long userId, Long companyId) throws SQLException {
        statement.executeUpdate("""
            INSERT INTO budgets (
                name, amount, start_date, end_date, warning_threshold, status, spent,
                remaining_amount, usage_rate, warning_reached, risk_level, risk_message,
                user_id, ledger_id, category_id, created_at, updated_at, company_id
            ) VALUES (
                '  Operations budget  ', '1000.50', '2026-09-01', '2026-09-30', 85, 1,
                '250.00', '750.50', 0.25, 0, ' LOW ', ' Budget healthy ',
                %d, NULL, NULL, '2026-09-01T10:00:00Z', '2026-09-01T11:00:00Z', %s
            )
            """.formatted(userId, companyId == null ? "NULL" : companyId.toString()));
    }

    private static void migrateToFifteen(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .target("15")
            .load()
            .migrate();
    }

    private static void migrateLatest(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .load()
            .migrate();
    }

    private static Connection connection(PostgreSQLContainer<?> database) throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private static boolean containsMessage(Throwable failure, String expected) {
        return java.util.stream.Stream.iterate(failure, java.util.Objects::nonNull, Throwable::getCause)
            .map(Throwable::getMessage)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.joining("\n"))
            .contains(expected);
    }
}
