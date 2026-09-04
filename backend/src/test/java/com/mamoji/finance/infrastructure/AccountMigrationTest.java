package com.mamoji.finance.infrastructure;

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
class AccountMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void migrationConvertsLegacyAccountsAndEnforcesCompanyOwnershipAndValueConstraints() throws Exception {
        migrateToSeventeen(POSTGRES);
        long userId;
        long companyId;
        long otherCompanyId;
        long otherLedgerId;
        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            userId = insertUser(statement, "account-migration@mamoji.test");
            companyId = insertCompany(statement, userId, "Migration Company");
            otherCompanyId = insertCompany(statement, userId, "Other Company");
            insertMembership(statement, companyId, userId);
            insertMembership(statement, otherCompanyId, userId);
            long ledgerId = insertLedger(statement, userId, companyId, "Migration Ledger");
            otherLedgerId = insertLedger(statement, userId, otherCompanyId, "Other Ledger");
            insertLedgerMember(statement, ledgerId, userId);
            insertLedgerMember(statement, otherLedgerId, userId);
            insertAccount(statement, userId, companyId, ledgerId);
        }

        migrateLatest(POSTGRES);

        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                SELECT name, type, currency, balance, available_balance, credit_limit, frozen_amount,
                       include_in_net_worth, opened_at, last_reconciled_at, created_at, company_id,
                       pg_typeof(balance)::TEXT AS balance_type,
                       pg_typeof(include_in_net_worth)::TEXT AS include_type,
                       pg_typeof(opened_at)::TEXT AS opened_type,
                       pg_typeof(created_at)::TEXT AS created_type
                FROM accounts
                """)) {
                result.next();
                assertEquals("Migration account", result.getString("name"));
                assertEquals("bank", result.getString("type"));
                assertEquals("CNY", result.getString("currency"));
                assertEquals("-125.5000", result.getBigDecimal("balance").toPlainString());
                assertEquals("900.2500", result.getBigDecimal("available_balance").toPlainString());
                assertEquals("2000.0000", result.getBigDecimal("credit_limit").toPlainString());
                assertEquals("12.5000", result.getBigDecimal("frozen_amount").toPlainString());
                assertTrue(result.getBoolean("include_in_net_worth"));
                assertEquals("2026-01-02", result.getObject("opened_at").toString());
                assertEquals("2026-08-31", result.getObject("last_reconciled_at").toString());
                assertEquals(companyId, result.getLong("company_id"));
                assertEquals("numeric", result.getString("balance_type"));
                assertEquals("boolean", result.getString("include_type"));
                assertEquals("date", result.getString("opened_type"));
                assertEquals("timestamp with time zone", result.getString("created_type"));
                assertFalse(result.next());
            }
            try (ResultSet version = statement.executeQuery("""
                SELECT version FROM flyway_schema_history
                WHERE success = true ORDER BY installed_rank DESC LIMIT 1
                """)) {
                version.next();
                assertEquals("25", version.getString("version"));
            }
            try (ResultSet constraints = statement.executeQuery("""
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'accounts'::regclass
                  AND conname IN (
                    'fk_accounts_company', 'fk_accounts_ledger', 'fk_accounts_user',
                    'fk_accounts_company_ledger', 'ck_accounts_company_positive',
                    'ck_accounts_name', 'ck_accounts_type', 'ck_accounts_currency',
                    'ck_accounts_credit_limit', 'ck_accounts_frozen_amount',
                    'ck_accounts_status', 'ck_accounts_reconciliation_status',
                    'ck_accounts_risk_level', 'ck_accounts_reconciliation_dates',
                    'ck_accounts_lifecycle', 'ck_accounts_version'
                  )
                  AND convalidated
                """)) {
                Set<String> names = new HashSet<>();
                while (constraints.next()) {
                    names.add(constraints.getString("conname"));
                }
                assertEquals(Set.of(
                    "fk_accounts_company",
                    "fk_accounts_ledger",
                    "fk_accounts_user",
                    "fk_accounts_company_ledger",
                    "ck_accounts_company_positive",
                    "ck_accounts_name",
                    "ck_accounts_type",
                    "ck_accounts_currency",
                    "ck_accounts_credit_limit",
                    "ck_accounts_frozen_amount",
                    "ck_accounts_status",
                    "ck_accounts_reconciliation_status",
                    "ck_accounts_risk_level",
                    "ck_accounts_reconciliation_dates",
                    "ck_accounts_lifecycle",
                    "ck_accounts_version"
                ), names);
            }
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE accounts SET credit_limit = -1"
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE accounts SET frozen_amount = -1"
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE accounts SET currency = 'cny'"
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE accounts SET status = 2"
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE accounts SET company_id = NULL"
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE accounts SET ledger_id = " + otherLedgerId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE accounts SET company_id = " + otherCompanyId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM users WHERE id = " + userId
            ));
        }
    }

    @Test
    void migrationRejectsUnscopedAccountsWithoutPartialUpgrade() throws Exception {
        try (PostgreSQLContainer<?> dirtyDatabase = new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            dirtyDatabase.start();
            migrateToSeventeen(dirtyDatabase);
            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                long userId = insertUser(statement, "dirty-account@mamoji.test");
                insertDirtyAccount(statement, userId);
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
                    assertEquals("17", version.getString("version"));
                }
                try (ResultSet type = statement.executeQuery("""
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'accounts'
                      AND column_name = 'balance'
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

    private static long insertLedger(Statement statement, long userId, long companyId, String name)
        throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO ledgers (
                name, description, currency, owner_id, is_default, status,
                created_at, updated_at, company_id
            ) VALUES (
                '%s', 'Migration fixture', 'CNY', %d, 0, 1,
                '2026-09-01T09:00:00Z', '2026-09-01T09:00:00Z', %d
            ) RETURNING id
            """.formatted(name, userId, companyId))) {
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

    private static void insertLedgerMember(Statement statement, long ledgerId, long userId)
        throws SQLException {
        statement.executeUpdate("""
            INSERT INTO ledger_members (ledger_id, user_id, role, nickname, avatar, joined_at)
            VALUES (%d, %d, 'owner', 'Migration owner', '', '2026-09-01T09:00:00Z')
            """.formatted(ledgerId, userId));
    }

    private static void insertAccount(Statement statement, long userId, long companyId, long ledgerId)
        throws SQLException {
        statement.executeUpdate("""
            INSERT INTO accounts (
                name, type, sub_type, bank, account_no, opening_bank, currency,
                balance, available_balance, credit_limit, frozen_amount,
                include_in_net_worth, user_id, ledger_id, status, opened_at,
                last_reconciled_at, owner_name, purpose, reconciliation_status,
                risk_level, created_at, updated_at, company_id, version
            ) VALUES (
                '  Migration account  ', ' BANK ', ' Corporate ', ' Test bank ', ' 1234 ',
                ' Test branch ', 'CNY', '-125.5000', '900.2500', '2000', '12.5',
                1, %d, %d, 1, ' 2026-01-02 ', ' 2026-08-31 ', ' Owner ',
                ' Working capital ', ' RECONCILED ', ' LOW ',
                ' 2026-09-01T09:00:00Z ', ' 2026-09-01T10:00:00Z ', %d, 2
            )
            """.formatted(userId, ledgerId, companyId));
    }

    private static void insertDirtyAccount(Statement statement, long userId) throws SQLException {
        statement.executeUpdate("""
            INSERT INTO accounts (
                name, type, currency, balance, available_balance, credit_limit, frozen_amount,
                include_in_net_worth, user_id, ledger_id, status, reconciliation_status,
                risk_level, created_at, updated_at, company_id
            ) VALUES (
                'Dirty account', 'cash', 'CNY', '10', '10', '0', '0',
                1, %d, NULL, 1, 'pending', 'low',
                '2026-09-01T09:00:00Z', '2026-09-01T09:00:00Z', NULL
            )
            """.formatted(userId));
    }

    private static void migrateToSeventeen(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .target("17")
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
