package com.mamoji.recurring.infrastructure;

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
class RecurringItemMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void migrationConvertsLegacyRulesAndEnforcesOwnershipAndValueConstraints() throws Exception {
        migrateToFourteen(POSTGRES);
        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            long userId = insertUser(statement);
            long companyId = insertCompany(statement, userId);
            statement.executeUpdate("""
                INSERT INTO recurring_items (
                    id, user_id, company_id, name, type, amount, frequency, interval_value,
                    day_of_week, day_of_month, month_of_year, start_date, end_date,
                    last_executed, next_execution, status, execution_count, note
                ) VALUES (
                    ' AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA ', %d, %d, '  Office rent  ', 2,
                    '3200.50', ' MONTHLY ', 1, NULL, 5, NULL, '2026-09-01', '2027-09-01',
                    NULL, '2026-10-05', 1, 0, 'migration fixture'
                )
                """.formatted(userId, companyId));
        }

        migrateLatest(POSTGRES);

        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                SELECT id, name, amount, frequency,
                       pg_typeof(amount)::TEXT AS amount_type,
                       pg_typeof(start_date)::TEXT AS start_type,
                       pg_typeof(next_execution)::TEXT AS next_type
                FROM recurring_items
                """)) {
                result.next();
                assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", result.getString("id"));
                assertEquals("Office rent", result.getString("name"));
                assertEquals("3200.50", result.getBigDecimal("amount").toPlainString());
                assertEquals("monthly", result.getString("frequency"));
                assertEquals("numeric", result.getString("amount_type"));
                assertEquals("date", result.getString("start_type"));
                assertEquals("date", result.getString("next_type"));
                assertFalse(result.next());
            }
            try (ResultSet version = statement.executeQuery("""
                SELECT version FROM flyway_schema_history
                WHERE success = true ORDER BY installed_rank DESC LIMIT 1
                """)) {
                version.next();
                assertEquals("26", version.getString("version"));
            }
            try (ResultSet constraints = statement.executeQuery("""
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'recurring_items'::regclass
                  AND conname IN (
                    'fk_recurring_company', 'fk_recurring_user', 'ck_recurring_amount',
                    'ck_recurring_interval', 'ck_recurring_execution_cursor'
                  )
                  AND convalidated
                """)) {
                Set<String> names = new java.util.HashSet<>();
                while (constraints.next()) {
                    names.add(constraints.getString("conname"));
                }
                assertEquals(Set.of(
                    "fk_recurring_company",
                    "fk_recurring_user",
                    "ck_recurring_amount",
                    "ck_recurring_interval",
                    "ck_recurring_execution_cursor"
                ), names);
            }
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE recurring_items SET interval_value = 0
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE recurring_items SET next_execution = start_date
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                DELETE FROM users WHERE email = 'recurring-migration@mamoji.test'
                """));
        }
    }

    @Test
    void migrationRejectsUnscopedRulesWithoutPartialUpgrade() throws Exception {
        try (PostgreSQLContainer<?> dirtyDatabase = new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            dirtyDatabase.start();
            migrateToFourteen(dirtyDatabase);
            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                long userId = insertUser(statement);
                statement.executeUpdate("""
                    INSERT INTO recurring_items (
                        id, user_id, company_id, name, type, amount, frequency, interval_value,
                        start_date, next_execution, status, execution_count
                    ) VALUES (
                        'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', %d, NULL, 'Rent', 2,
                        '100.00', 'monthly', 1, '2026-09-01', '2026-10-01', 1, 0
                    )
                    """.formatted(userId));
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
                    assertEquals("14", version.getString("version"));
                }
                try (ResultSet type = statement.executeQuery("""
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'recurring_items'
                      AND column_name = 'amount'
                    """)) {
                    type.next();
                    assertEquals("text", type.getString("data_type"));
                }
            }
        }
    }

    private static long insertUser(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO users (
                email, nickname, avatar, family_id, role, permissions,
                password_hash, created_at, updated_at
            ) VALUES (
                'recurring-migration@mamoji.test', 'Migration owner', '', NULL, 1, 15,
                'not-used', NOW(), NOW()
            ) RETURNING id
            """)) {
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

    private static void migrateToFourteen(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .target("14")
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
