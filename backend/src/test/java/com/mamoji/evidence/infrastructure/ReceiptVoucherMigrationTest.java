package com.mamoji.evidence.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ReceiptVoucherMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void migrationHydratesLegacyReceiptDefaultsExactlyOnce() throws Exception {
        migrateToTwentySix(POSTGRES);
        long purchaseId;
        long reimbursementId;
        long completeId;
        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            long ownerId = insertUser(statement, "receipt-migration@mamoji.test");
            long companyId = insertCompany(statement, ownerId, "Receipt migration company");
            purchaseId = insertReceipt(
                statement,
                companyId,
                ownerId,
                "purchase_invoice",
                "expense",
                "6000.00",
                "300.00",
                "2026-08-30",
                "linked",
                "not_required",
                "not_applicable",
                "",
                "not_required",
                "",
                null,
                " ",
                null,
                "receipt.pdf",
                "",
                0
            );
            statement.executeUpdate(
                "UPDATE receipt_vouchers SET tax_period = '2026-09' WHERE id = " + purchaseId
            );
            reimbursementId = insertReceipt(
                statement,
                companyId,
                ownerId,
                "reimbursement",
                "expense",
                "100.00",
                "0",
                "2026-07-15",
                "archived",
                "",
                "",
                "not_applicable",
                "not_required",
                "",
                null,
                "",
                null,
                null,
                "",
                0
            );
            completeId = insertReceipt(
                statement,
                companyId,
                ownerId,
                "bank_slip",
                "expense",
                "25",
                "0",
                "2026-09-01",
                "pending_review",
                "not_required",
                "not_applicable",
                "not_applicable",
                "not_required",
                "not_started",
                null,
                "complete entry",
                null,
                null,
                "none",
                7
            );
            statement.executeUpdate(
                "UPDATE receipt_vouchers SET tax_period = '2026-09' WHERE id = " + completeId
            );
        }

        migrateLatest(POSTGRES);

        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                SELECT tax_period, invoice_check_status, deduction_status, reimbursement_status,
                       approval_status, accounting_status, accounting_voucher_no, accounting_entry,
                       accounted_at, file_storage_provider, version
                FROM receipt_vouchers WHERE id = %d
                """.formatted(purchaseId))) {
                result.next();
                assertEquals("2026-09", result.getString("tax_period"));
                assertEquals("verified", result.getString("invoice_check_status"));
                assertEquals("deductible", result.getString("deduction_status"));
                assertEquals("not_applicable", result.getString("reimbursement_status"));
                assertEquals("not_submitted", result.getString("approval_status"));
                assertEquals("posted", result.getString("accounting_status"));
                assertEquals(voucherNumber("202608", purchaseId), result.getString("accounting_voucher_no"));
                assertEquals(
                    "借：管理费用 5700，应交税费-进项税额 300；贷：应付账款 6000",
                    result.getString("accounting_entry")
                );
                assertEquals("2026-08-31T09:00:00Z", result.getString("accounted_at"));
                assertEquals("metadata_only", result.getString("file_storage_provider"));
                assertEquals(1, result.getLong("version"));
            }
            try (ResultSet result = statement.executeQuery("""
                SELECT tax_period, invoice_check_status, deduction_status, reimbursement_status,
                       approval_status, accounting_status, accounting_voucher_no, accounting_entry,
                       accounted_at, file_storage_provider, version
                FROM receipt_vouchers WHERE id = %d
                """.formatted(reimbursementId))) {
                result.next();
                assertEquals("2026-07", result.getString("tax_period"));
                assertEquals("verified", result.getString("invoice_check_status"));
                assertEquals("deductible", result.getString("deduction_status"));
                assertEquals("archived", result.getString("reimbursement_status"));
                assertEquals("not_submitted", result.getString("approval_status"));
                assertEquals("posted", result.getString("accounting_status"));
                assertEquals(voucherNumber("202607", reimbursementId), result.getString("accounting_voucher_no"));
                assertEquals("借：管理费用 100；贷：其他应付款-员工 100", result.getString("accounting_entry"));
                assertEquals("2026-08-31T09:00:00Z", result.getString("accounted_at"));
                assertEquals("none", result.getString("file_storage_provider"));
                assertEquals(1, result.getLong("version"));
            }
            assertEquals(7, version(statement, completeId));
            assertEquals("29", latestVersion(statement));
        }
    }

    @Test
    void migrationRejectsInvalidMoneyWithoutPartialHydration() throws Exception {
        try (PostgreSQLContainer<?> dirtyDatabase = new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            dirtyDatabase.start();
            migrateToTwentySix(dirtyDatabase);
            long receiptId;
            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                long ownerId = insertUser(statement, "dirty-receipt@mamoji.test");
                long companyId = insertCompany(statement, ownerId, "Dirty receipt company");
                receiptId = insertReceipt(
                    statement,
                    companyId,
                    ownerId,
                    "bank_slip",
                    "expense",
                    "not-money",
                    "0",
                    "2026-09-01",
                    "pending_review",
                    "not_required",
                    "not_applicable",
                    "not_applicable",
                    "not_required",
                    "not_started",
                    null,
                    "complete entry",
                    null,
                    null,
                    "none",
                    0
                );
            }

            FlywayException failure = assertThrows(FlywayException.class, () -> migrateLatest(dirtyDatabase));
            assertTrue(containsMessage(failure, "receipt_vouchers contains an invalid amount, tax amount, or tax rate"));

            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                assertEquals("26", latestVersion(statement));
                assertEquals(0, version(statement, receiptId));
            }
        }
    }

    private static long insertUser(Statement statement, String email) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO users (
                email, nickname, avatar, family_id, role, permissions,
                password_hash, created_at, updated_at
            ) VALUES (
                '%s', 'Receipt migration user', '', NULL, 1, 15,
                'not-used', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            ) RETURNING id
            """.formatted(email))) {
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

    private static long insertReceipt(
        Statement statement,
        long companyId,
        long operatorId,
        String voucherType,
        String direction,
        String amount,
        String taxAmount,
        String issueDate,
        String status,
        String invoiceCheckStatus,
        String deductionStatus,
        String reimbursementStatus,
        String approvalStatus,
        String accountingStatus,
        String accountingVoucherNo,
        String accountingEntry,
        String accountedAt,
        String fileName,
        String fileStorageProvider,
        long version
    ) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO receipt_vouchers (
                company_id, transaction_id, voucher_no, title, voucher_type, direction, counterparty,
                amount, tax_amount, tax_rate, tax_period, invoice_check_status, deduction_status,
                reimbursement_status, approval_status, accounting_status, accounting_voucher_no,
                accounting_entry, accounted_at, issue_date, status, file_name, file_size, file_type,
                file_storage_provider, risk_level, operator_user_id, created_at, updated_at, version
            ) VALUES (
                %d, NULL, 'RV-' || md5(random()::TEXT), 'Legacy receipt', '%s', '%s', 'Vendor',
                '%s', '%s', '0', ' ', '%s', '%s', '%s', '%s', '%s', %s, %s, %s,
                '%s', '%s', %s, 10, 'application/pdf', '%s', 'low', %d,
                '2026-08-31T08:00:00Z', '2026-08-31T09:00:00Z', %d
            ) RETURNING id
            """.formatted(
                companyId,
                voucherType,
                direction,
                amount,
                taxAmount,
                invoiceCheckStatus,
                deductionStatus,
                reimbursementStatus,
                approvalStatus,
                accountingStatus,
                sqlValue(accountingVoucherNo),
                sqlValue(accountingEntry),
                sqlValue(accountedAt),
                issueDate,
                status,
                sqlValue(fileName),
                fileStorageProvider,
                operatorId,
                version
            ))) {
            result.next();
            return result.getLong("id");
        }
    }

    private static long version(Statement statement, long receiptId) throws SQLException {
        try (ResultSet result = statement.executeQuery(
            "SELECT version FROM receipt_vouchers WHERE id = " + receiptId
        )) {
            result.next();
            return result.getLong("version");
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

    private static String voucherNumber(String period, long id) {
        return "JV-" + period + "-" + String.format("%04d", id);
    }

    private static String sqlValue(String value) {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
    }

    private static Connection connection(PostgreSQLContainer<?> database) throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private static void migrateToTwentySix(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .target("26")
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
