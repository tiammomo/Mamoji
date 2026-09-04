package com.mamoji.platform.tenant;

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
class CompanyMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void migrationTypesNormalizesAndProtectsTenantProfiles() throws Exception {
        migrateToTwentyFour(POSTGRES);
        long ownerId;
        long otherOwnerId;
        long companyId;
        long legacyCompanyId;
        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            ownerId = insertUser(statement, "company-owner@mamoji.test");
            otherOwnerId = insertUser(statement, "other-company-owner@mamoji.test");
            companyId = insertCompany(statement, ownerId, "  Tenant Company  ", "  cn-001  ");
            legacyCompanyId = insertCompany(statement, ownerId, "深圳历史主体", "legacy-cn-002");
            statement.executeUpdate("""
                UPDATE companies
                SET entity_type = ' COMPANY ', currency = ' cny ', industry = ' Software ',
                    taxpayer_type = ' General ', country = ' China ', province = ' Guangdong ',
                    city = ' Shenzhen ', district = ' Nanshan ', registered_address = ' 1 Road ',
                    operating_region = ' China/Guangdong/Shenzhen ', tax_authority = ' Shenzhen Tax ',
                    policy_profile_key = ' CN-GD-SZ ', created_at = ' 2026-09-01T08:00:00Z ',
                    updated_at = ' 2026-09-02T08:00:00Z '
                WHERE id = %d
                """.formatted(companyId));
            statement.executeUpdate("""
                UPDATE companies
                SET entity_type = '', country = '', province = '', city = '',
                    operating_region = '', policy_profile_key = 'CN-GD-SZ-DEMO-POLICY',
                    fiscal_year_start_month = 0
                WHERE id = %d
                """.formatted(legacyCompanyId));
        }

        migrateLatest(POSTGRES);

        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                SELECT version, name, entity_type, credit_code, industry, currency,
                       pg_typeof(created_at)::TEXT AS created_type
                FROM companies WHERE id = %d
                """.formatted(companyId))) {
                result.next();
                assertEquals(0, result.getLong("version"));
                assertEquals("Tenant Company", result.getString("name"));
                assertEquals("company", result.getString("entity_type"));
                assertEquals("CN-001", result.getString("credit_code"));
                assertEquals("Software", result.getString("industry"));
                assertEquals("CNY", result.getString("currency"));
                assertEquals("timestamp with time zone", result.getString("created_type"));
            }
            try (ResultSet result = statement.executeQuery("""
                SELECT entity_type, country, province, city, operating_region,
                       policy_profile_key, fiscal_year_start_month
                FROM companies WHERE id = %d
                """.formatted(legacyCompanyId))) {
                result.next();
                assertEquals("company", result.getString("entity_type"));
                assertEquals("中国", result.getString("country"));
                assertEquals("广东省", result.getString("province"));
                assertEquals("深圳市", result.getString("city"));
                assertEquals("中国/广东省/深圳市", result.getString("operating_region"));
                assertEquals("CN-GD-SZ-STARTUP-LITE", result.getString("policy_profile_key"));
                assertEquals(1, result.getInt("fiscal_year_start_month"));
            }
            assertEquals("26", latestVersion(statement));
            assertTrue(companyConstraints(statement).containsAll(Set.of(
                "fk_companies_owner",
                "ck_companies_version",
                "ck_companies_entity_type",
                "ck_companies_currency",
                "ck_companies_fiscal_year",
                "ck_companies_lifecycle"
            )));
            assertTrue(hasCreditCodeIndex(statement));

            assertThrows(SQLException.class, () -> insertCompany(
                statement,
                otherOwnerId,
                "Duplicate credit code",
                "cn-001"
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE companies SET currency = 'CN' WHERE id = " + companyId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE companies SET version = -1 WHERE id = " + companyId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE companies SET owner_id = " + otherOwnerId + " WHERE id = " + companyId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM users WHERE id = " + ownerId
            ));
        }
    }

    @Test
    void migrationRejectsDuplicateCreditCodesWithoutPartialUpgrade() throws Exception {
        try (PostgreSQLContainer<?> dirtyDatabase = new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            dirtyDatabase.start();
            migrateToTwentyFour(dirtyDatabase);
            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                long ownerId = insertUser(statement, "dirty-company@mamoji.test");
                insertCompany(statement, ownerId, "First tenant", " DUPLICATE ");
                insertCompany(statement, ownerId, "Second tenant", "duplicate");
            }

            FlywayException failure = assertThrows(FlywayException.class, () -> migrateLatest(dirtyDatabase));
            assertTrue(containsMessage(failure, "companies contains duplicate normalized credit codes"));

            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                assertEquals("24", latestVersion(statement));
                assertFalse(hasColumn(statement, "version"));
                try (ResultSet column = statement.executeQuery("""
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'companies'
                      AND column_name = 'created_at'
                    """)) {
                    column.next();
                    assertEquals("text", column.getString("data_type"));
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
                '%s', 'Company migration user', '', NULL, 1, 15,
                'not-used', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            ) RETURNING id
            """.formatted(email))) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long insertCompany(Statement statement, long ownerId, String name, String creditCode)
        throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO companies (
                name, entity_type, credit_code, industry, taxpayer_type, currency, country,
                province, city, district, operating_region, policy_profile_key,
                fiscal_year_start_month, owner_id, created_at, updated_at
            ) VALUES (
                '%s', 'company', '%s', 'test', 'test', 'CNY', 'China',
                '', '', '', 'China', 'TEST-POLICY', 1, %d,
                '2026-09-01T08:00:00Z', '2026-09-01T08:00:00Z'
            ) RETURNING id
            """.formatted(name, creditCode, ownerId))) {
            result.next();
            return result.getLong(1);
        }
    }

    private static Set<String> companyConstraints(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT conname FROM pg_constraint
            WHERE conrelid = 'companies'::regclass AND convalidated
            """)) {
            Set<String> names = new HashSet<>();
            while (result.next()) names.add(result.getString(1));
            return names;
        }
    }

    private static boolean hasCreditCodeIndex(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT EXISTS(
                SELECT 1 FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname = 'uq_companies_normalized_credit_code'
            )
            """)) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private static boolean hasColumn(Statement statement, String columnName) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT EXISTS(
                SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'companies'
                  AND column_name = '%s'
            )
            """.formatted(columnName))) {
            result.next();
            return result.getBoolean(1);
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

    private static void migrateToTwentyFour(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .target("24")
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
