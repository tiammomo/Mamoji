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
class EntityTransferMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void migrationTypesNormalizesAndProtectsEntityTransfers() throws Exception {
        migrateToTwentyFive(POSTGRES);
        long operatorId;
        long sourceId;
        long targetId;
        long transferId;
        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            operatorId = insertUser(statement, "transfer-operator@mamoji.test");
            sourceId = insertCompany(statement, operatorId, "Transfer Source");
            targetId = insertCompany(statement, operatorId, "Transfer Target");
            transferId = insertTransfer(
                statement,
                sourceId,
                targetId,
                operatorId,
                " SHAREHOLDER_ADVANCE ",
                " 125.5000 ",
                " cny ",
                " 2026-09-04 ",
                " working capital ",
                " RECORDED "
            );
        }

        migrateLatest(POSTGRES);

        try (Connection connection = connection(POSTGRES); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                SELECT transfer_type, amount, currency, transfer_date, note, status,
                       pg_typeof(amount)::TEXT AS amount_type,
                       pg_typeof(transfer_date)::TEXT AS date_type,
                       pg_typeof(created_at)::TEXT AS created_type
                FROM entity_transfers WHERE id = %d
                """.formatted(transferId))) {
                result.next();
                assertEquals("shareholder_advance", result.getString("transfer_type"));
                assertEquals("125.5000", result.getBigDecimal("amount").toPlainString());
                assertEquals("CNY", result.getString("currency"));
                assertEquals("2026-09-04", result.getDate("transfer_date").toLocalDate().toString());
                assertEquals("working capital", result.getString("note"));
                assertEquals("recorded", result.getString("status"));
                assertEquals("numeric", result.getString("amount_type"));
                assertEquals("date", result.getString("date_type"));
                assertEquals("timestamp with time zone", result.getString("created_type"));
            }
            assertEquals("32", latestVersion(statement));
            assertTrue(transferConstraints(statement).containsAll(Set.of(
                "fk_entity_transfers_source",
                "fk_entity_transfers_target",
                "fk_entity_transfers_operator",
                "ck_entity_transfers_distinct_subjects",
                "ck_entity_transfers_amount",
                "ck_entity_transfers_status",
                "ck_entity_transfers_lifecycle"
            )));
            assertTrue(hasIndex(statement, "idx_entity_transfers_pair_date"));

            assertThrows(SQLException.class, () -> insertTransfer(
                statement, sourceId, sourceId, operatorId, "inter_entity_transfer", "1", "CNY",
                "2026-09-04", null, "recorded"
            ));
            assertThrows(SQLException.class, () -> insertTransfer(
                statement, sourceId, targetId, 999999, "inter_entity_transfer", "1", "CNY",
                "2026-09-04", null, "recorded"
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE entity_transfers SET amount = 2 WHERE id = " + transferId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM entity_transfers WHERE id = " + transferId
            ));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "DELETE FROM companies WHERE id = " + sourceId
            ));
        }
    }

    @Test
    void migrationRejectsInvalidTransferWithoutPartialUpgrade() throws Exception {
        try (PostgreSQLContainer<?> dirtyDatabase = new PostgreSQLContainer<>("postgres:18.4-alpine")) {
            dirtyDatabase.start();
            migrateToTwentyFive(dirtyDatabase);
            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                long operatorId = insertUser(statement, "dirty-transfer@mamoji.test");
                long sourceId = insertCompany(statement, operatorId, "Dirty Source");
                long targetId = insertCompany(statement, operatorId, "Dirty Target");
                insertTransfer(
                    statement, sourceId, targetId, operatorId, "cash_move", "10", "CNY",
                    "2026-09-04", null, "recorded"
                );
            }

            FlywayException failure = assertThrows(FlywayException.class, () -> migrateLatest(dirtyDatabase));
            assertTrue(containsMessage(failure, "entity_transfers contains invalid transfer attributes"));

            try (Connection connection = connection(dirtyDatabase); Statement statement = connection.createStatement()) {
                assertEquals("25", latestVersion(statement));
                assertFalse(hasIndex(statement, "idx_entity_transfers_pair_date"));
                try (ResultSet column = statement.executeQuery("""
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'entity_transfers'
                      AND column_name = 'amount'
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
                '%s', 'Transfer migration user', '', NULL, 1, 15,
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
            return result.getLong(1);
        }
    }

    private static long insertTransfer(
        Statement statement,
        long sourceId,
        long targetId,
        long operatorId,
        String type,
        String amount,
        String currency,
        String date,
        String note,
        String status
    ) throws SQLException {
        String noteValue = note == null ? "NULL" : "'" + note + "'";
        try (ResultSet result = statement.executeQuery("""
            INSERT INTO entity_transfers (
                from_entity_id, to_entity_id, transfer_type, amount, currency, transfer_date,
                note, status, operator_user_id, created_at, updated_at
            ) VALUES (
                %d, %d, '%s', '%s', '%s', '%s', %s, '%s', %d,
                '2026-09-04T08:00:00Z', '2026-09-04T09:00:00Z'
            ) RETURNING id
            """.formatted(sourceId, targetId, type, amount, currency, date, noteValue, status, operatorId))) {
            result.next();
            return result.getLong(1);
        }
    }

    private static Set<String> transferConstraints(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT conname FROM pg_constraint
            WHERE conrelid = 'entity_transfers'::regclass AND convalidated
            """)) {
            Set<String> names = new HashSet<>();
            while (result.next()) names.add(result.getString(1));
            return names;
        }
    }

    private static boolean hasIndex(Statement statement, String indexName) throws SQLException {
        try (ResultSet result = statement.executeQuery("""
            SELECT EXISTS(
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
            return result.getString(1);
        }
    }

    private static Connection connection(PostgreSQLContainer<?> database) throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private static void migrateToTwentyFive(PostgreSQLContainer<?> database) {
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
            .target("25")
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
