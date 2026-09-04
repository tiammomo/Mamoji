package com.mamoji.people.infrastructure;

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
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class EmploymentEventMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void migrationTypesAndProtectsAppendOnlyCompanyScopedEvents() throws Exception {
        migrateToTwentyThree(POSTGRES);
        long operatorId;
        long companyId;
        long otherCompanyId;
        long employeeId;
        long eventId;
        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            operatorId = insertUser(statement, "event-operator@mamoji.test");
            companyId = insertCompany(statement, operatorId, "Employment event company");
            otherCompanyId = insertCompany(statement, operatorId, "Other event company");
            employeeId = insertEmployee(statement, companyId, "event-employee@mamoji.test");
            eventId = insertEvent(
                statement,
                companyId,
                employeeId,
                "  ONBOARD  ",
                "  员工入职  ",
                operatorId
            );
        }

        migrateLatest(POSTGRES);

        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                SELECT type, note,
                       pg_typeof(effective_date)::TEXT AS effective_date_type,
                       pg_typeof(created_at)::TEXT AS created_at_type
                FROM employment_events WHERE id = %d
                """.formatted(eventId))) {
                result.next();
                assertEquals("onboard", result.getString("type"));
                assertEquals("员工入职", result.getString("note"));
                assertEquals("date", result.getString("effective_date_type"));
                assertEquals("timestamp with time zone", result.getString("created_at_type"));
            }
            assertEquals("25", latestVersion(statement));
            assertTrue(eventConstraints(statement).containsAll(Set.of(
                "fk_employment_events_company",
                "fk_employment_events_employee_company",
                "fk_employment_events_operator",
                "ck_employment_events_type",
                "ck_employment_events_note"
            )));
            assertEquals(2, newEventIndexCount(statement));

            long outsiderId = insertEmployee(statement, otherCompanyId, "event-outsider@mamoji.test");
            assertThrows(SQLException.class, () -> insertEvent(
                statement,
                companyId,
                outsiderId,
                "status_change",
                "跨公司员工",
                operatorId
            ));
            assertThrows(SQLException.class, () -> insertEvent(
                statement,
                companyId,
                employeeId,
                "unknown",
                "非法类型",
                operatorId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE employment_events SET note = 'changed' WHERE id = " + eventId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM employees WHERE id = " + employeeId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM users WHERE id = " + operatorId
            ));
        }
    }

    @Test
    void migrationRejectsCrossCompanyEmployeeWithoutPartialUpgrade() throws Exception {
        try (PostgreSQLContainer<?> dirtyDatabase = new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            dirtyDatabase.start();
            migrateToTwentyThree(dirtyDatabase);
            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                long operatorId = insertUser(statement, "dirty-event@mamoji.test");
                long companyId = insertCompany(statement, operatorId, "Event owner");
                long otherCompanyId = insertCompany(statement, operatorId, "Employee owner");
                long outsiderId = insertEmployee(statement, otherCompanyId, "dirty-event-employee@mamoji.test");
                insertEvent(statement, companyId, outsiderId, "onboard", "跨公司事件", operatorId);
            }

            FlywayException failure = assertThrows(FlywayException.class, () -> migrateLatest(dirtyDatabase));
            assertTrue(containsMessage(failure, "employment_events contains an orphaned or cross-company employee"));

            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                assertEquals("23", latestVersion(statement));
                try (ResultSet column = statement.executeQuery("""
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'employment_events'
                      AND column_name = 'effective_date'
                    """)) {
                    column.next();
                    assertEquals("text", column.getString("data_type"));
                }
            }
        }
    }

    private static Set<String> eventConstraints(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT conname FROM pg_constraint
            WHERE conrelid = 'employment_events'::regclass AND convalidated
            """)) {
            Set<String> names = new HashSet<>();
            while (result.next()) names.add(result.getString(1));
            return names;
        }
    }

    private static int newEventIndexCount(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT COUNT(*) FROM pg_indexes
            WHERE schemaname = current_schema()
              AND indexname IN (
                  'idx_employment_events_employee_date',
                  'idx_employment_events_operator_created'
              )
            """)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static long insertUser(Statement statement, String email) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO users (
                email, nickname, avatar, family_id, role, permissions,
                password_hash, created_at, updated_at
            ) VALUES (
                '%s', 'Employment event user', '', NULL, 1, 15,
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
                name, entity_type, industry, taxpayer_type, currency, owner_id, created_at, updated_at
            ) VALUES (
                '%s', 'company', 'test', 'test', 'CNY', %d, '2026-09-01', '2026-09-01'
            ) RETURNING id
            """.formatted(name, ownerId))) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long insertEmployee(Statement statement, long companyId, String email) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO employees (
                company_id, name, email, position, employment_type, status, access_role, access_scope,
                hire_date, salary, social_insurance, housing_fund, tax_estimate, monthly_cost,
                created_at, updated_at
            ) VALUES (
                %d, 'Event employee', '%s', 'Engineer', 'full_time', 'active', 'employee', 'self',
                '2026-09-01', 10000, 0, 0, 0, 10000,
                '2026-09-01T08:00:00Z', '2026-09-01T08:00:00Z'
            ) RETURNING id
            """.formatted(companyId, email))) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long insertEvent(
        Statement statement,
        long companyId,
        long employeeId,
        String type,
        String note,
        long operatorId
    ) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO employment_events (
                company_id, employee_id, type, effective_date, note, operator_user_id, created_at
            ) VALUES (
                %d, %d, '%s', '2026-09-01', '%s', %d, '2026-09-01T08:00:00Z'
            ) RETURNING id
            """.formatted(companyId, employeeId, type, note, operatorId))) {
            result.next();
            return result.getLong(1);
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

    private static void migrateToTwentyThree(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .target("23")
            .load()
            .migrate();
    }

    private static void migrateLatest(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .load()
            .migrate();
    }

    private static boolean containsMessage(Throwable error, String expected) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) return true;
            current = current.getCause();
        }
        return false;
    }
}
