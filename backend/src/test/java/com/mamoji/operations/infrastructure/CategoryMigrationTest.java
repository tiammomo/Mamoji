package com.mamoji.operations.infrastructure;

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
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class CategoryMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void migrationTypesAndScopesCategoriesAndProtectsAccountingSemantics() throws Exception {
        migrateToNineteen(POSTGRES);
        long userId;
        long companyId;
        long otherCompanyId;
        long categoryId;
        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            userId = insertUser(statement, "category-migration@mamoji.test");
            companyId = insertCompany(statement, userId, "Category migration company");
            otherCompanyId = insertCompany(statement, userId, "Other category company");
            insertMembership(statement, companyId, userId);
            insertMembership(statement, otherCompanyId, userId);
            categoryId = insertCategory(statement, userId, companyId, "  Migration category  ", "  📦  ", " #AABBCC ", " EXPENSE ");
        }

        migrateLatest(POSTGRES);

        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                SELECT name, icon, color, type, company_id, created_at,
                       pg_typeof(created_at)::TEXT AS created_type,
                       pg_typeof(updated_at)::TEXT AS updated_type
                FROM categories WHERE id = %d
                """.formatted(categoryId))) {
                result.next();
                assertEquals("Migration category", result.getString("name"));
                assertEquals("📦", result.getString("icon"));
                assertEquals("#aabbcc", result.getString("color"));
                assertEquals("expense", result.getString("type"));
                assertEquals(companyId, result.getLong("company_id"));
                assertEquals("timestamp with time zone", result.getString("created_type"));
                assertEquals("timestamp with time zone", result.getString("updated_type"));
                assertFalse(result.next());
            }
            assertEquals("26", latestVersion(statement));
            assertEquals(Set.of(
                "uq_categories_company_id",
                "uq_categories_company_user_type_name",
                "fk_categories_company",
                "fk_categories_user",
                "fk_categories_company_user",
                "ck_categories_company_positive",
                "ck_categories_name",
                "ck_categories_icon",
                "ck_categories_color",
                "ck_categories_type",
                "ck_categories_status",
                "ck_categories_lifecycle"
            ), constraints(statement));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO categories (
                    name, icon, color, type, user_id, status, created_at, updated_at, company_id
                ) VALUES (
                    'Migration category', 'M', '#112233', 'expense', %d, 1,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, %d
                )
                """.formatted(userId, companyId)));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE categories SET company_id = " + otherCompanyId + " WHERE id = " + categoryId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE categories SET color = '#XYZXYZ' WHERE id = " + categoryId
            ));
            insertBudgetReference(statement, userId, companyId, categoryId);
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE categories SET type = 'income' WHERE id = " + categoryId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM company_memberships WHERE company_id = " + companyId + " AND user_id = " + userId
            ));
        }
    }

    @Test
    void migrationRejectsUnscopedCategoriesWithoutPartialUpgrade() throws Exception {
        try (PostgreSQLContainer<?> dirtyDatabase = new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            dirtyDatabase.start();
            migrateToNineteen(dirtyDatabase);
            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                long userId = insertUser(statement, "unscoped-category@mamoji.test");
                insertCategory(statement, userId, null, "Dirty category", "D", "#112233", "expense");
            }

            FlywayException failure = assertThrows(FlywayException.class, () -> migrateLatest(dirtyDatabase));
            assertTrue(containsMessage(failure, "unscoped or orphaned company member"));

            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                assertEquals("19", latestVersion(statement));
                try (ResultSet column = statement.executeQuery("""
                    SELECT data_type, is_nullable
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'categories'
                      AND column_name = 'created_at'
                    """)) {
                    column.next();
                    assertEquals("text", column.getString("data_type"));
                    assertEquals("NO", column.getString("is_nullable"));
                }
                try (ResultSet companyColumn = statement.executeQuery("""
                    SELECT is_nullable
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'categories'
                      AND column_name = 'company_id'
                    """)) {
                    companyColumn.next();
                    assertEquals("YES", companyColumn.getString("is_nullable"));
                }
            }
        }
    }

    private static Set<String> constraints(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT conname
            FROM pg_constraint
            WHERE conrelid = 'categories'::regclass
              AND convalidated
              AND (conname LIKE 'uq_categories_%'
                OR conname LIKE 'fk_categories_%'
                OR conname LIKE 'ck_categories_%')
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
                '%s', 'Migration owner', '', NULL, 1, 15,
                'not-used', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            ) RETURNING id
            """.formatted(email))) {
            result.next();
            return result.getLong("id");
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
            return result.getLong("id");
        }
    }

    private static void insertMembership(Statement statement, long companyId, long userId)
        throws SQLException {
        statement.executeUpdate("""
            INSERT INTO company_memberships (
                company_id, user_id, role, scope, status, created_at, updated_at
            ) VALUES (
                %d, %d, 'founder', 'company', 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """.formatted(companyId, userId));
    }

    private static long insertCategory(
        Statement statement,
        long userId,
        Long companyId,
        String name,
        String icon,
        String color,
        String type
    ) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO categories (
                name, icon, color, type, user_id, status, created_at, updated_at, company_id
            ) VALUES (
                '%s', '%s', '%s', '%s', %d, 1,
                ' 2026-09-01T09:00:00Z ', ' 2026-09-01T10:00:00Z ', %s
            ) RETURNING id
            """.formatted(name, icon, color, type, userId, companyId == null ? "NULL" : companyId))) {
            result.next();
            return result.getLong("id");
        }
    }

    private static void insertBudgetReference(Statement statement, long userId, long companyId, long categoryId)
        throws SQLException {
        statement.executeUpdate("""
            INSERT INTO budgets (
                name, amount, start_date, end_date, warning_threshold, status, spent,
                remaining_amount, usage_rate, warning_reached, risk_level, risk_message,
                user_id, ledger_id, category_id, created_at, updated_at, company_id
            ) VALUES (
                'Category reference', 100, DATE '2026-09-01', DATE '2026-09-30', 80, 1, 0,
                100, 0, FALSE, 'low', 'Budget healthy', %d, NULL, %d,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, %d
            )
            """.formatted(userId, categoryId, companyId));
    }

    private static String latestVersion(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT version FROM flyway_schema_history
            WHERE success = true ORDER BY installed_rank DESC LIMIT 1
            """)) {
            result.next();
            return result.getString("version");
        }
    }

    private static Connection connection(PostgreSQLContainer<?> database) throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private static void migrateToNineteen(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .target("19")
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
