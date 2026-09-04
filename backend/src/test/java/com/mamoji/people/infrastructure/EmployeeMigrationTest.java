package com.mamoji.people.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
class EmployeeMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void migrationTypesEmployeesAndProtectsCompanyScopedRelationships() throws Exception {
        migrateToTwentyTwo(POSTGRES);
        long ownerId;
        long linkedUserId;
        long companyId;
        long otherCompanyId;
        long managerId;
        long employeeId;
        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            ownerId = insertUser(statement, "employee-owner@mamoji.test");
            linkedUserId = insertUser(statement, "linked-employee@mamoji.test");
            companyId = insertCompany(statement, ownerId, "Employee migration company");
            otherCompanyId = insertCompany(statement, ownerId, "Other employee company");
            managerId = insertEmployee(
                statement, companyId, null, null, "Manager", "manager@mamoji.test", "full_time", "active"
            );
            employeeId = insertEmployee(
                statement, companyId, linkedUserId, managerId, "  Employee  ", "  PERSON@MAMOJI.TEST  ",
                "probation", "probation"
            );
            statement.executeUpdate("""
                UPDATE employees
                SET employee_no = '  E-001  ', material_status = 'complete',
                    profile_verified_at = ' 2026-09-02T08:00:00Z ', profile_verified_by = %d,
                    salary = '10000.1250', leave_date = ' 2026-12-31 ',
                    created_at = ' 2026-09-01T08:00:00Z ', updated_at = ' 2026-09-02T08:00:00Z '
                WHERE id = %d
                """.formatted(linkedUserId, employeeId));
        }

        migrateLatest(POSTGRES);

        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                SELECT name, email, employee_no, employment_type, material_status, salary,
                       pg_typeof(hire_date)::TEXT AS hire_type,
                       pg_typeof(salary)::TEXT AS salary_type,
                       pg_typeof(created_at)::TEXT AS created_type
                FROM employees WHERE id = %d
                """.formatted(employeeId))) {
                result.next();
                assertEquals("Employee", result.getString("name"));
                assertEquals("person@mamoji.test", result.getString("email"));
                assertEquals("E-001", result.getString("employee_no"));
                assertEquals("full_time", result.getString("employment_type"));
                assertEquals("verified", result.getString("material_status"));
                assertEquals("10000.1250", result.getBigDecimal("salary").toPlainString());
                assertEquals("date", result.getString("hire_type"));
                assertEquals("numeric", result.getString("salary_type"));
                assertEquals("timestamp with time zone", result.getString("created_type"));
            }
            assertEquals("27", latestVersion(statement));
            assertTrue(employeeConstraints(statement).containsAll(Set.of(
                "fk_employees_company",
                "fk_employees_user",
                "fk_employees_profile_verifier",
                "fk_employees_direct_manager_company",
                "ck_employees_employment_type",
                "ck_employees_status",
                "ck_employees_amounts_nonnegative",
                "ck_employees_rate_range",
                "ck_employees_lifecycle"
            )));
            assertEquals(3, employeeUniqueIndexCount(statement));

            assertThrows(SQLException.class, () -> insertEmployee(
                statement, companyId, null, null, "Duplicate", "PERSON@mamoji.test", "full_time", "active"
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE employees SET salary = -1 WHERE id = " + employeeId
            ));
            long outsiderId = insertEmployee(
                statement, otherCompanyId, null, null, "Outsider", "outsider@mamoji.test", "full_time", "active"
            );
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE employees SET direct_manager_employee_id = " + outsiderId + " WHERE id = " + employeeId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE employees SET company_id = " + otherCompanyId + " WHERE id = " + employeeId
            ));

            statement.executeUpdate("DELETE FROM users WHERE id = " + linkedUserId);
            try (ResultSet references = statement.executeQuery(
                "SELECT user_id, profile_verified_by FROM employees WHERE id = " + employeeId
            )) {
                references.next();
                assertNull(references.getObject("user_id"));
                assertNull(references.getObject("profile_verified_by"));
            }
            statement.executeUpdate("DELETE FROM employees WHERE id = " + managerId);
            try (ResultSet manager = statement.executeQuery(
                "SELECT direct_manager_employee_id FROM employees WHERE id = " + employeeId
            )) {
                manager.next();
                assertNull(manager.getObject(1));
            }
        }
    }

    @Test
    void migrationRejectsCrossCompanyManagerWithoutPartialUpgrade() throws Exception {
        try (PostgreSQLContainer<?> dirtyDatabase = new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            dirtyDatabase.start();
            migrateToTwentyTwo(dirtyDatabase);
            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                long ownerId = insertUser(statement, "dirty-employee@mamoji.test");
                long companyId = insertCompany(statement, ownerId, "Employee owner");
                long otherCompanyId = insertCompany(statement, ownerId, "Manager owner");
                long managerId = insertEmployee(
                    statement, otherCompanyId, null, null, "Manager", "other-manager@mamoji.test",
                    "full_time", "active"
                );
                insertEmployee(
                    statement, companyId, null, managerId, "Employee", "dirty-person@mamoji.test",
                    "full_time", "active"
                );
            }

            FlywayException failure = assertThrows(FlywayException.class, () -> migrateLatest(dirtyDatabase));
            assertTrue(containsMessage(failure, "employees contains an invalid or cross-company direct manager"));

            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                assertEquals("22", latestVersion(statement));
                try (ResultSet column = statement.executeQuery("""
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'employees'
                      AND column_name = 'salary'
                    """)) {
                    column.next();
                    assertEquals("text", column.getString("data_type"));
                }
            }
        }
    }

    private static Set<String> employeeConstraints(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT conname FROM pg_constraint
            WHERE conrelid = 'employees'::regclass AND convalidated
            """)) {
            Set<String> names = new HashSet<>();
            while (result.next()) names.add(result.getString(1));
            return names;
        }
    }

    private static int employeeUniqueIndexCount(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT COUNT(*) FROM pg_indexes
            WHERE schemaname = current_schema()
              AND indexname IN (
                  'uq_employees_company_normalized_email',
                  'uq_employees_company_normalized_employee_no',
                  'uq_employees_company_user'
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
                '%s', 'Employee migration user', '', NULL, 1, 15,
                'not-used', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            ) RETURNING id
            """.formatted(email))) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long insertCompany(Statement statement, long userId, String name) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO companies (
                name, entity_type, industry, taxpayer_type, currency, owner_id, created_at, updated_at
            ) VALUES (
                '%s', 'company', 'test', 'test', 'CNY', %d, '2026-09-01', '2026-09-01'
            ) RETURNING id
            """.formatted(name, userId))) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long insertEmployee(
        Statement statement,
        long companyId,
        Long userId,
        Long managerId,
        String name,
        String email,
        String employmentType,
        String status
    ) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO employees (
                company_id, user_id, direct_manager_employee_id, name, email, position, employment_type, status,
                access_role, access_scope, hire_date, salary, social_insurance, housing_fund, tax_estimate,
                monthly_cost, created_at, updated_at
            ) VALUES (
                %d, %s, %s, '%s', '%s', 'Engineer', '%s', '%s',
                'employee', 'self', '2026-09-01', '10000', '0', '0', '0',
                '10000', '2026-09-01T08:00:00Z', '2026-09-01T08:00:00Z'
            ) RETURNING id
            """.formatted(
                companyId,
                userId == null ? "NULL" : userId,
                managerId == null ? "NULL" : managerId,
                name,
                email,
                employmentType,
                status
            ))) {
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

    private static void migrateToTwentyTwo(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .target("22")
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
