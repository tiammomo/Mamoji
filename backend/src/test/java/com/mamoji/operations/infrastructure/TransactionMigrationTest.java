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
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class TransactionMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void migrationConvertsLegacyTransactionsAndEnforcesOwnershipAndLifecycleConstraints() throws Exception {
        migrateToSixteen(POSTGRES);
        long companyId;
        long otherCompanyId;
        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            long userId = insertUser(statement, "transaction-migration@mamoji.test");
            companyId = insertCompany(statement, userId, "Migration Company");
            otherCompanyId = insertCompany(statement, userId, "Other Company");
            insertMembership(statement, companyId, userId);
            insertMembership(statement, otherCompanyId, userId);
            long accountId = insertAccount(statement, userId, companyId);
            long categoryId = insertCategory(statement, userId, companyId);
            long originalId = insertExpense(statement, userId, companyId, accountId, categoryId);
            insertRefund(statement, userId, companyId, accountId, categoryId, originalId);
        }

        migrateLatest(POSTGRES);

        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                SELECT amount, refunded_amount, date, is_refundable, created_at,
                       pg_typeof(amount)::TEXT AS amount_type,
                       pg_typeof(date)::TEXT AS date_type,
                       pg_typeof(is_refundable)::TEXT AS refundable_type,
                       pg_typeof(created_at)::TEXT AS created_type
                FROM transactions
                WHERE type = 2
                """)) {
                result.next();
                assertEquals("125.5000", result.getBigDecimal("amount").toPlainString());
                assertEquals("25.0000", result.getBigDecimal("refunded_amount").toPlainString());
                assertEquals("2026-09-01", result.getObject("date").toString());
                assertTrue(result.getBoolean("is_refundable"));
                assertEquals("numeric", result.getString("amount_type"));
                assertEquals("date", result.getString("date_type"));
                assertEquals("boolean", result.getString("refundable_type"));
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
                WHERE conrelid = 'transactions'::regclass
                  AND conname IN (
                    'fk_transactions_company', 'fk_transactions_user',
                    'fk_transactions_company_account', 'fk_transactions_company_category',
                    'fk_transactions_company_original', 'fk_transactions_company_original_user',
                    'ck_transactions_amount',
                    'ck_transactions_refund_state', 'ck_transactions_original',
                    'ck_transactions_lifecycle', 'ck_transactions_version'
                  )
                  AND convalidated
                """)) {
                Set<String> names = new HashSet<>();
                while (constraints.next()) {
                    names.add(constraints.getString("conname"));
                }
                assertEquals(Set.of(
                    "fk_transactions_company",
                    "fk_transactions_user",
                    "fk_transactions_company_account",
                    "fk_transactions_company_category",
                    "fk_transactions_company_original",
                    "fk_transactions_company_original_user",
                    "ck_transactions_amount",
                    "ck_transactions_refund_state",
                    "ck_transactions_original",
                    "ck_transactions_lifecycle",
                    "ck_transactions_version"
                ), names);
            }
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE transactions SET amount = 0 WHERE type = 2"
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE transactions SET refunded_amount = amount + 1 WHERE type = 2"
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE transactions SET company_id = NULL WHERE type = 2"
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE transactions SET company_id = " + otherCompanyId + " WHERE type = 2"
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE transactions SET original_transaction_id = id WHERE type = 2"
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                DELETE FROM users WHERE email = 'transaction-migration@mamoji.test'
                """));
            assertEquals(2, statement.executeUpdate(
                "UPDATE transactions SET note = note WHERE company_id = " + companyId
            ));
        }
    }

    @Test
    void migrationRejectsUnscopedTransactionsWithoutPartialUpgrade() throws Exception {
        try (PostgreSQLContainer<?> dirtyDatabase = new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            dirtyDatabase.start();
            migrateToSixteen(dirtyDatabase);
            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                long userId = insertUser(statement, "dirty-transaction@mamoji.test");
                long companyId = insertCompany(statement, userId, "Dirty Company");
                insertMembership(statement, companyId, userId);
                long accountId = insertAccount(statement, userId, companyId);
                long categoryId = insertCategory(statement, userId, companyId);
                insertTransaction(statement, userId, null, accountId, categoryId, null, 2, "100.00", "0", 1,
                    "dirty:transaction");
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
                    assertEquals("16", version.getString("version"));
                }
                try (ResultSet type = statement.executeQuery("""
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'transactions'
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

    private static long insertAccount(Statement statement, long userId, long companyId) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO accounts (
                name, type, currency, balance, available_balance, credit_limit, frozen_amount,
                include_in_net_worth, user_id, ledger_id, status, reconciliation_status,
                risk_level, created_at, updated_at, company_id
            ) VALUES (
                'Migration account', 'bank', 'CNY', '1000', '1000', '0', '0',
                1, %d, NULL, 1, 'reconciled', 'low',
                '2026-09-01T09:00:00Z', '2026-09-01T09:00:00Z', %d
            ) RETURNING id
            """.formatted(userId, companyId))) {
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

    private static long insertCategory(Statement statement, long userId, long companyId) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO categories (
                name, icon, color, type, user_id, status, created_at, updated_at, company_id
            ) VALUES (
                'Migration expense', 'receipt', '#000000', 'expense', %d, 1,
                '2026-09-01T09:00:00Z', '2026-09-01T09:00:00Z', %d
            ) RETURNING id
            """.formatted(userId, companyId))) {
            result.next();
            return result.getLong("id");
        }
    }

    private static long insertExpense(
        Statement statement,
        long userId,
        long companyId,
        long accountId,
        long categoryId
    ) throws SQLException {
        return insertTransaction(statement, userId, companyId, accountId, categoryId, null, 2,
            "125.5000", "25.0000", 1, "migration:expense");
    }

    private static long insertRefund(
        Statement statement,
        long userId,
        long companyId,
        long accountId,
        long categoryId,
        long originalId
    ) throws SQLException {
        return insertTransaction(statement, userId, companyId, accountId, categoryId, originalId, 3,
            "25.0000", "0", 0, "migration:refund");
    }

    private static long insertTransaction(
        Statement statement,
        long userId,
        Long companyId,
        long accountId,
        long categoryId,
        Long originalId,
        int type,
        String amount,
        String refundedAmount,
        int refundable,
        String idempotencyKey
    ) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO transactions (
                user_id, family_id, type, amount, category_id, account_id, date, note,
                original_transaction_id, refunded_amount, is_refundable, budget_id,
                created_at, updated_at, company_id, idempotency_key, version
            ) VALUES (
                %d, NULL, %d, '%s', %d, %d, '2026-09-01', 'Migration fixture',
                %s, '%s', %d, NULL, '2026-09-01T10:00:00Z',
                '2026-09-01T11:00:00Z', %s, '%s', 2
            ) RETURNING id
            """.formatted(
                userId,
                type,
                amount,
                categoryId,
                accountId,
                originalId == null ? "NULL" : originalId.toString(),
                refundedAmount,
                refundable,
                companyId == null ? "NULL" : companyId.toString(),
                idempotencyKey
            ))) {
            result.next();
            return result.getLong("id");
        }
    }

    private static void migrateToSixteen(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .target("16")
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
