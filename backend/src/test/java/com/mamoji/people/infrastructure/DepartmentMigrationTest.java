package com.mamoji.people.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
class DepartmentMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void migrationTypesDepartmentsAndProtectsCompanyScopedAssignments() throws Exception {
        migrateToTwentyOne(POSTGRES);
        long userId;
        long companyId;
        long otherCompanyId;
        long departmentId;
        long managerId;
        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            userId = insertUser(statement, "department-migration@mamoji.test");
            companyId = insertCompany(statement, userId, "Department migration company");
            otherCompanyId = insertCompany(statement, userId, "Other department company");
            departmentId = insertDepartment(statement, companyId, "  Product  ", "  RND  ", "1000.50");
            managerId = insertEmployee(statement, companyId, departmentId, "Manager");
            statement.executeUpdate("UPDATE departments SET manager_employee_id = " + managerId
                + " WHERE id = " + departmentId);
            insertMembership(statement, companyId, userId, departmentId);
        }

        migrateLatest(POSTGRES);

        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                SELECT name, cost_center, budget,
                       pg_typeof(budget)::TEXT AS budget_type,
                       pg_typeof(status)::TEXT AS status_type,
                       pg_typeof(created_at)::TEXT AS created_type
                FROM departments WHERE id = %d
                """.formatted(departmentId))) {
                result.next();
                assertEquals("Product", result.getString("name"));
                assertEquals("RND", result.getString("cost_center"));
                assertEquals("1000.50", result.getBigDecimal("budget").toPlainString());
                assertEquals("numeric", result.getString("budget_type"));
                assertEquals("smallint", result.getString("status_type"));
                assertEquals("timestamp with time zone", result.getString("created_type"));
                assertFalse(result.next());
            }
            assertEquals("24", latestVersion(statement));
            assertEquals(Set.of(
                "uq_departments_id_company",
                "fk_departments_company",
                "fk_departments_manager_company",
                "ck_departments_company_positive",
                "ck_departments_name",
                "ck_departments_cost_center",
                "ck_departments_budget",
                "ck_departments_status",
                "ck_departments_lifecycle",
                "uq_employees_id_company",
                "fk_employees_department_company",
                "fk_company_memberships_department_company"
            ), organizationConstraints(statement));
            try (ResultSet index = statement.executeQuery("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname = 'uq_departments_company_normalized_name'
                """)) {
                index.next();
                assertEquals(1, index.getInt(1));
            }
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO departments (
                    company_id, name, cost_center, manager_employee_id, budget, status, created_at, updated_at
                ) VALUES (%d, 'product', 'OTHER', NULL, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(companyId)));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE departments SET company_id = " + otherCompanyId + " WHERE id = " + departmentId
            ));
            long outsiderId = insertEmployee(statement, otherCompanyId, null, "Outsider");
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE departments SET manager_employee_id = " + outsiderId + " WHERE id = " + departmentId
            ));

            statement.executeUpdate("DELETE FROM employees WHERE id = " + managerId);
            try (ResultSet manager = statement.executeQuery(
                "SELECT manager_employee_id FROM departments WHERE id = " + departmentId
            )) {
                manager.next();
                assertNull(manager.getObject(1));
            }
            long memberId = insertEmployee(statement, companyId, departmentId, "Member");
            statement.executeUpdate("DELETE FROM departments WHERE id = " + departmentId);
            try (ResultSet employee = statement.executeQuery(
                "SELECT department_id FROM employees WHERE id = " + memberId
            )) {
                employee.next();
                assertNull(employee.getObject(1));
            }
            try (ResultSet membership = statement.executeQuery(
                "SELECT department_id FROM company_memberships WHERE company_id = " + companyId
            )) {
                membership.next();
                assertNull(membership.getObject(1));
            }
        }
    }

    @Test
    void migrationRejectsCrossCompanyDepartmentAssignmentsWithoutPartialUpgrade() throws Exception {
        try (PostgreSQLContainer<?> dirtyDatabase = new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            dirtyDatabase.start();
            migrateToTwentyOne(dirtyDatabase);
            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                long userId = insertUser(statement, "dirty-department@mamoji.test");
                long companyId = insertCompany(statement, userId, "Department owner");
                long otherCompanyId = insertCompany(statement, userId, "Employee owner");
                long departmentId = insertDepartment(statement, companyId, "Product", "RND", "100");
                insertEmployee(statement, otherCompanyId, departmentId, "Cross-company employee");
            }

            FlywayException failure = assertThrows(FlywayException.class, () -> migrateLatest(dirtyDatabase));
            assertTrue(containsMessage(failure, "employees contains an orphaned or cross-company department"));

            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                assertEquals("21", latestVersion(statement));
                try (ResultSet column = statement.executeQuery("""
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'departments'
                      AND column_name = 'budget'
                    """)) {
                    column.next();
                    assertEquals("text", column.getString("data_type"));
                }
            }
        }
    }

    private static Set<String> organizationConstraints(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT conname
            FROM pg_constraint
            WHERE conname IN (
                'uq_departments_id_company',
                'fk_departments_company',
                'fk_departments_manager_company',
                'ck_departments_company_positive',
                'ck_departments_name',
                'ck_departments_cost_center',
                'ck_departments_budget',
                'ck_departments_status',
                'ck_departments_lifecycle',
                'uq_employees_id_company',
                'fk_employees_department_company',
                'fk_company_memberships_department_company'
            ) AND convalidated
            """)) {
            Set<String> names = new HashSet<>();
            while (result.next()) names.add(result.getString("conname"));
            return names;
        }
    }

    private static long insertUser(Statement statement, String email) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO users (
                email, nickname, avatar, family_id, role, permissions,
                password_hash, created_at, updated_at
            ) VALUES (
                '%s', 'Department owner', '', NULL, 1, 15,
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

    private static long insertDepartment(
        Statement statement,
        long companyId,
        String name,
        String costCenter,
        String budget
    ) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO departments (
                company_id, name, cost_center, manager_employee_id, budget, status, created_at, updated_at
            ) VALUES (
                %d, '%s', '%s', NULL, '%s', 1,
                ' 2026-09-01T09:00:00Z ', ' 2026-09-01T10:00:00Z '
            ) RETURNING id
            """.formatted(companyId, name, costCenter, budget))) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long insertEmployee(Statement statement, long companyId, Long departmentId, String name)
        throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO employees (
                company_id, department_id, name, email, position, employment_type, status,
                hire_date, salary, social_insurance, housing_fund, tax_estimate, monthly_cost,
                created_at, updated_at
            ) VALUES (
                %d, %s, '%s', '%s@mamoji.test', 'Engineer', 'full_time', 'active',
                '2026-09-01', '0', '0', '0', '0', '0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            ) RETURNING id
            """.formatted(
                companyId,
                departmentId == null ? "NULL" : departmentId,
                name,
                name.toLowerCase().replace(' ', '-') + "-" + System.nanoTime()
            ))) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void insertMembership(
        Statement statement,
        long companyId,
        long userId,
        long departmentId
    ) throws SQLException {
        statement.executeUpdate("""
            INSERT INTO company_memberships (
                company_id, user_id, department_id, role, scope, status, created_at, updated_at
            ) VALUES (
                %d, %d, %d, 'founder', 'company', 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """.formatted(companyId, userId, departmentId));
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

    private static void migrateToTwentyOne(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .target("21")
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
