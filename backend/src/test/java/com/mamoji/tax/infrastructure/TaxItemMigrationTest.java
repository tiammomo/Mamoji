package com.mamoji.tax.infrastructure;

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
class TaxItemMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void migrationTypesNormalizesAndProtectsCompanyTaxPeriods() throws Exception {
        migrateToTwenty(POSTGRES);
        long userId;
        long companyId;
        long otherCompanyId;
        long taxItemId;
        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            userId = insertUser(statement, "tax-migration@mamoji.test");
            companyId = insertCompany(statement, userId, "Tax migration company");
            otherCompanyId = insertCompany(statement, userId, "Other tax company");
            taxItemId = insertTaxItem(statement, companyId, "1000.0000", "100.0000", "25.0000");
        }

        migrateLatest(POSTGRES);

        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                SELECT name, period, tax_type, taxable_amount, tax_amount, paid_amount,
                       responsible_person, policy_basis, source_type, note,
                       pg_typeof(tax_amount)::TEXT AS amount_type,
                       pg_typeof(due_date)::TEXT AS due_type,
                       pg_typeof(created_at)::TEXT AS created_type
                FROM tax_items WHERE id = %d
                """.formatted(taxItemId))) {
                result.next();
                assertEquals("Migration VAT", result.getString("name"));
                assertEquals("2026-Q3", result.getString("period"));
                assertEquals("vat", result.getString("tax_type"));
                assertEquals("Finance owner", result.getString("responsible_person"));
                assertEquals("CN-VAT-TEST", result.getString("policy_basis"));
                assertEquals("manual", result.getString("source_type"));
                assertEquals("migration note", result.getString("note"));
                assertEquals("numeric", result.getString("amount_type"));
                assertEquals("date", result.getString("due_type"));
                assertEquals("timestamp with time zone", result.getString("created_type"));
                assertFalse(result.next());
            }
            assertEquals("33", latestVersion(statement));
            assertEquals(Set.of(
                "uq_tax_items_company_type_period",
                "fk_tax_items_company",
                "ck_tax_items_company_positive",
                "ck_tax_items_name",
                "ck_tax_items_period_frequency",
                "ck_tax_items_tax_type",
                "ck_tax_items_amounts",
                "ck_tax_items_status",
                "ck_tax_items_filing_status",
                "ck_tax_items_payment_status",
                "ck_tax_items_paid_lifecycle",
                "ck_tax_items_frequency",
                "ck_tax_items_responsible_person",
                "ck_tax_items_risk_level",
                "ck_tax_items_policy_basis",
                "ck_tax_items_source_type",
                "ck_tax_items_note",
                "ck_tax_items_lifecycle"
            ), constraints(statement));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO tax_items (
                    company_id, name, period, tax_type, taxable_amount, tax_amount, paid_amount,
                    deductible_amount, tax_rate, due_date, status, filing_status, payment_status,
                    frequency, declaration_date, payment_date, responsible_person, risk_level,
                    policy_basis, source_type, note, created_at, updated_at
                ) SELECT
                    company_id, 'Duplicate VAT', period, tax_type, taxable_amount, tax_amount, paid_amount,
                    deductible_amount, tax_rate, due_date, status, filing_status, payment_status,
                    frequency, declaration_date, payment_date, responsible_person, risk_level,
                    policy_basis, source_type, note, created_at, updated_at
                FROM tax_items WHERE id = %d
                """.formatted(taxItemId)));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE tax_items SET company_id = " + otherCompanyId + " WHERE id = " + taxItemId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE tax_items SET paid_amount = 101 WHERE id = " + taxItemId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                UPDATE tax_items
                SET period = '2026-02-31', frequency = 'one_time'
                WHERE id = %d
                """.formatted(taxItemId)));
        }
    }

    @Test
    void migrationRejectsInvalidLifecycleWithoutPartialUpgrade() throws Exception {
        try (PostgreSQLContainer<?> dirtyDatabase = new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            dirtyDatabase.start();
            migrateToTwenty(dirtyDatabase);
            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                long userId = insertUser(statement, "dirty-tax@mamoji.test");
                long companyId = insertCompany(statement, userId, "Dirty tax company");
                insertTaxItem(statement, companyId, "100", "10", "11");
            }

            FlywayException failure = assertThrows(FlywayException.class, () -> migrateLatest(dirtyDatabase));
            assertTrue(containsMessage(failure, "out-of-range monetary or rate value"));

            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                assertEquals("20", latestVersion(statement));
                try (ResultSet column = statement.executeQuery("""
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'tax_items'
                      AND column_name = 'tax_amount'
                    """)) {
                    column.next();
                    assertEquals("text", column.getString("data_type"));
                }
            }
        }
    }

    private static Set<String> constraints(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT conname
            FROM pg_constraint
            WHERE conrelid = 'tax_items'::regclass
              AND convalidated
              AND (conname LIKE 'uq_tax_items_%'
                OR conname LIKE 'fk_tax_items_%'
                OR conname LIKE 'ck_tax_items_%')
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

    private static long insertTaxItem(
        Statement statement,
        long companyId,
        String taxableAmount,
        String taxAmount,
        String paidAmount
    ) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO tax_items (
                company_id, name, period, tax_type, taxable_amount, tax_amount, paid_amount,
                deductible_amount, tax_rate, due_date, status, filing_status, payment_status,
                frequency, declaration_date, payment_date, responsible_person, risk_level,
                policy_basis, source_type, note, created_at, updated_at
            ) VALUES (
                %d, '  Migration VAT  ', ' 2026-q3 ', ' VAT ', '%s', '%s', '%s',
                ' 5.0000 ', ' 10.0000 ', ' 2026-10-15 ', ' PENDING ', ' SUBMITTED ', ' PARTIAL ',
                ' QUARTERLY ', ' 2026-09-30 ', NULL, ' Finance owner ', ' MEDIUM ',
                ' CN-VAT-TEST ', ' MANUAL ', ' migration note ',
                ' 2026-09-01T09:00:00Z ', ' 2026-09-01T10:00:00Z '
            ) RETURNING id
            """.formatted(companyId, taxableAmount, taxAmount, paidAmount))) {
            result.next();
            return result.getLong("id");
        }
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

    private static void migrateToTwenty(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .target("20")
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
