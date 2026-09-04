package com.mamoji.evidence.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
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
        long fileHashId;
        long companyId;
        long otherCompanyId;
        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            long ownerId = insertUser(statement, "receipt-migration@mamoji.test");
            companyId = insertCompany(statement, ownerId, "Receipt migration company");
            otherCompanyId = insertCompany(statement, ownerId, "Other receipt migration company");
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
            try (ResultSet result = statement.executeQuery("""
                INSERT INTO receipt_file_hashes (
                    company_id, voucher_id, sha256, file_name, file_size, created_at
                ) VALUES (
                    %d, %d, '  %s  ', '  C:\\fakepath\\complete.pdf  ', 10, '  2026-08-31T09:00:00Z  '
                ) RETURNING id
                """.formatted(companyId, completeId, "A".repeat(64)))) {
                result.next();
                fileHashId = result.getLong("id");
            }
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
                assertEquals(
                    OffsetDateTime.parse("2026-08-31T09:00:00Z"),
                    result.getObject("accounted_at", OffsetDateTime.class)
                );
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
                assertEquals(
                    OffsetDateTime.parse("2026-08-31T09:00:00Z"),
                    result.getObject("accounted_at", OffsetDateTime.class)
                );
                assertEquals("none", result.getString("file_storage_provider"));
                assertEquals(1, result.getLong("version"));
            }
            assertEquals(7, version(statement, completeId));
            assertEquals("32", latestVersion(statement));

            try (ResultSet types = statement.executeQuery("""
                SELECT pg_typeof(amount)::TEXT AS amount_type,
                       pg_typeof(tax_rate)::TEXT AS tax_rate_type,
                       pg_typeof(issue_date)::TEXT AS issue_date_type,
                       pg_typeof(file_size)::TEXT AS file_size_type,
                       pg_typeof(created_at)::TEXT AS created_at_type
                FROM receipt_vouchers WHERE id = %d
                """.formatted(purchaseId))) {
                types.next();
                assertEquals("numeric", types.getString("amount_type"));
                assertEquals("numeric", types.getString("tax_rate_type"));
                assertEquals("date", types.getString("issue_date_type"));
                assertEquals("bigint", types.getString("file_size_type"));
                assertEquals("timestamp with time zone", types.getString("created_at_type"));
            }
            try (ResultSet fileHash = statement.executeQuery("""
                SELECT sha256, file_name, file_size, created_at,
                       pg_typeof(created_at)::TEXT AS created_at_type
                FROM receipt_file_hashes WHERE id = %d
                """.formatted(fileHashId))) {
                fileHash.next();
                assertEquals("a".repeat(64), fileHash.getString("sha256"));
                assertEquals("complete.pdf", fileHash.getString("file_name"));
                assertEquals(10, fileHash.getLong("file_size"));
                assertEquals(
                    OffsetDateTime.parse("2026-08-31T09:00:00Z"),
                    fileHash.getObject("created_at", OffsetDateTime.class)
                );
                assertEquals("timestamp with time zone", fileHash.getString("created_at_type"));
            }
            try (ResultSet fileHashColumns = statement.executeQuery("""
                SELECT column_name, character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'receipt_file_hashes'
                  AND column_name IN ('sha256', 'file_name')
                """)) {
                java.util.Map<String, Integer> lengths = new java.util.HashMap<>();
                while (fileHashColumns.next()) {
                    lengths.put(
                        fileHashColumns.getString("column_name"),
                        fileHashColumns.getInt("character_maximum_length")
                    );
                }
                assertEquals(java.util.Map.of("sha256", 64, "file_name", 255), lengths);
            }
            try (ResultSet constraints = statement.executeQuery("""
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'receipt_vouchers'::regclass
                  AND conname IN (
                    'uq_receipt_vouchers_company_id',
                    'fk_receipt_vouchers_company',
                    'fk_receipt_vouchers_company_transaction',
                    'fk_receipt_vouchers_operator',
                    'fk_receipt_vouchers_approver',
                    'ck_receipt_vouchers_amounts',
                    'ck_receipt_vouchers_dates',
                    'ck_receipt_vouchers_status',
                    'ck_receipt_vouchers_approval_audit',
                    'ck_receipt_vouchers_accounting_lifecycle',
                    'ck_receipt_vouchers_lifecycle',
                    'ck_receipt_vouchers_version'
                  )
                  AND convalidated
                """)) {
                Set<String> names = new HashSet<>();
                while (constraints.next()) names.add(constraints.getString("conname"));
                assertEquals(Set.of(
                    "uq_receipt_vouchers_company_id",
                    "fk_receipt_vouchers_company",
                    "fk_receipt_vouchers_company_transaction",
                    "fk_receipt_vouchers_operator",
                    "fk_receipt_vouchers_approver",
                    "ck_receipt_vouchers_amounts",
                    "ck_receipt_vouchers_dates",
                    "ck_receipt_vouchers_status",
                    "ck_receipt_vouchers_approval_audit",
                    "ck_receipt_vouchers_accounting_lifecycle",
                    "ck_receipt_vouchers_lifecycle",
                    "ck_receipt_vouchers_version"
                ), names);
            }
            try (ResultSet constraints = statement.executeQuery("""
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'receipt_file_hashes'::regclass
                  AND conname IN (
                    'fk_receipt_file_hashes_company',
                    'fk_receipt_file_hashes_company_voucher',
                    'ck_receipt_file_hashes_company_positive',
                    'ck_receipt_file_hashes_voucher_positive',
                    'ck_receipt_file_hashes_sha256',
                    'ck_receipt_file_hashes_file_name',
                    'ck_receipt_file_hashes_file_size',
                    'ck_receipt_file_hashes_created_at'
                  )
                  AND convalidated
                """)) {
                Set<String> names = new HashSet<>();
                while (constraints.next()) names.add(constraints.getString("conname"));
                assertEquals(Set.of(
                    "fk_receipt_file_hashes_company",
                    "fk_receipt_file_hashes_company_voucher",
                    "ck_receipt_file_hashes_company_positive",
                    "ck_receipt_file_hashes_voucher_positive",
                    "ck_receipt_file_hashes_sha256",
                    "ck_receipt_file_hashes_file_name",
                    "ck_receipt_file_hashes_file_size",
                    "ck_receipt_file_hashes_created_at"
                ), names);
            }
            try (ResultSet indexes = statement.executeQuery("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename = 'receipt_vouchers'
                  AND indexname IN (
                    'idx_receipt_vouchers_company_issue',
                    'idx_receipt_vouchers_company_status',
                    'idx_receipt_vouchers_company_voucher_type',
                    'idx_receipt_vouchers_company_missing_transaction'
                  )
                """)) {
                Set<String> names = new HashSet<>();
                while (indexes.next()) names.add(indexes.getString("indexname"));
                assertEquals(Set.of(
                    "idx_receipt_vouchers_company_issue",
                    "idx_receipt_vouchers_company_status",
                    "idx_receipt_vouchers_company_voucher_type",
                    "idx_receipt_vouchers_company_missing_transaction"
                ), names);
            }
            assertEquals(1, statement.executeUpdate(
                "UPDATE receipt_file_hashes SET file_name = file_name WHERE id = " + fileHashId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE receipt_file_hashes SET file_name = 'changed.pdf' WHERE id = " + fileHashId
            ));
            assertTrue(indexExists(statement, "idx_receipt_file_hashes_company_voucher"));
            assertFalse(indexExists(statement, "idx_receipt_file_hashes_voucher"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE receipt_vouchers SET tax_amount = amount + 1 WHERE id = " + purchaseId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE receipt_vouchers SET status = 'unknown' WHERE id = " + purchaseId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE receipt_vouchers SET due_date = issue_date - 1 WHERE id = " + purchaseId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE receipt_vouchers SET approved_by_user_id = operator_user_id WHERE id = " + purchaseId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE receipt_vouchers SET company_id = " + otherCompanyId + " WHERE id = " + purchaseId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO receipt_file_hashes (
                    company_id, voucher_id, sha256, file_name, file_size, created_at
                ) VALUES (
                    %d, %d, '%s', 'receipt.pdf', 10, CURRENT_TIMESTAMP
                )
                """.formatted(otherCompanyId, purchaseId, "b".repeat(64))));
            assertEquals(3, statement.executeUpdate(
                "UPDATE receipt_vouchers SET note = note WHERE company_id = " + companyId
            ));
        }
    }

    @Test
    void migrationRejectsInvalidReceiptClassificationWithoutPartialSchemaUpgrade() throws Exception {
        try (PostgreSQLContainer<?> dirtyDatabase = new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            dirtyDatabase.start();
            migrateToThirty(dirtyDatabase);
            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                long ownerId = insertUser(statement, "dirty-receipt-status@mamoji.test");
                long companyId = insertCompany(statement, ownerId, "Dirty receipt status company");
                insertReceipt(
                    statement,
                    companyId,
                    ownerId,
                    "bank_slip",
                    "expense",
                    "25.00",
                    "0",
                    "2026-09-01",
                    "unexpected",
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
            assertTrue(containsMessage(failure, "invalid classification or tax period"));

            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                assertEquals("30", latestVersion(statement));
                try (ResultSet type = statement.executeQuery("""
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'receipt_vouchers'
                      AND column_name = 'amount'
                    """)) {
                    type.next();
                    assertEquals("text", type.getString("data_type"));
                }
            }
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

    @Test
    void migrationRejectsInvalidFileDigestWithoutPartialSchemaUpgrade() throws Exception {
        try (PostgreSQLContainer<?> dirtyDatabase = new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            dirtyDatabase.start();
            migrateToThirtyOne(dirtyDatabase);
            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                long ownerId = insertUser(statement, "dirty-file-digest@mamoji.test");
                long companyId = insertCompany(statement, ownerId, "Dirty file digest company");
                long receiptId = insertTypedReceipt(statement, companyId, ownerId);
                statement.executeUpdate("""
                    INSERT INTO receipt_file_hashes (
                        company_id, voucher_id, sha256, file_name, file_size, created_at
                    ) VALUES (%d, %d, 'not-a-digest', 'receipt.pdf', 10, '2026-09-01T08:00:00Z')
                    """.formatted(companyId, receiptId));
            }

            FlywayException failure = assertThrows(FlywayException.class, () -> migrateLatest(dirtyDatabase));
            assertTrue(containsMessage(failure, "invalid SHA-256 digest"));

            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                assertEquals("31", latestVersion(statement));
                try (ResultSet type = statement.executeQuery("""
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'receipt_file_hashes'
                      AND column_name = 'created_at'
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

    private static long insertTypedReceipt(Statement statement, long companyId, long operatorId) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO receipt_vouchers (
                company_id, transaction_id, voucher_no, title, voucher_type, direction, counterparty,
                amount, tax_amount, tax_rate, tax_period, invoice_check_status, deduction_status,
                reimbursement_status, approval_status, accounting_status, accounting_voucher_no,
                accounting_entry, approved_by_user_id, approved_at, accounted_at, business_purpose,
                expense_owner, issue_date, due_date, status, file_name, file_size, file_type,
                file_storage_provider, file_bucket, file_object_key, file_url, risk_level, note,
                operator_user_id, created_at, updated_at, version
            ) VALUES (
                %d, NULL, 'RV-' || md5(random()::TEXT), 'Typed receipt', 'bank_slip', 'expense', 'Vendor',
                25, 0, 0, NULL, 'not_required', 'not_applicable', 'not_applicable', 'not_required',
                'not_started', NULL, NULL, NULL, NULL, NULL, NULL, NULL, DATE '2026-09-01', NULL,
                'pending_review', 'receipt.pdf', 10, 'application/pdf', 'metadata_only', NULL, NULL,
                NULL, 'low', NULL, %d, TIMESTAMPTZ '2026-09-01T08:00:00Z',
                TIMESTAMPTZ '2026-09-01T08:00:00Z', 0
            ) RETURNING id
            """.formatted(companyId, operatorId))) {
            result.next();
            return result.getLong("id");
        }
    }

    private static boolean indexExists(Statement statement, String indexName) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT EXISTS (
                SELECT 1 FROM pg_indexes
                WHERE schemaname = current_schema() AND indexname = '%s'
            )
            """.formatted(indexName))) {
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

    private static void migrateToThirty(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .target("30")
            .load()
            .migrate();
    }

    private static void migrateToThirtyOne(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .target("31")
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
