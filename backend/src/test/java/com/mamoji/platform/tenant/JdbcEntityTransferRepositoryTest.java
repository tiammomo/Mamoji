package com.mamoji.platform.tenant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcEntityTransferRepositoryTest {
    @Test
    void appendNormalizesAndBindsTypedTransferValues() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        JdbcEntityTransferRepository repository = new JdbcEntityTransferRepository(jdbc);
        EntityTransfer transfer = transfer();

        EntityTransfer saved = repository.append(transfer);

        assertEquals(41, saved.id);
        assertEquals("shareholder_advance", saved.transferType);
        assertEquals(new BigDecimal("125.5"), saved.amount);
        assertEquals("CNY", saved.currency);
        assertEquals("working capital", saved.note);
        assertEquals("Source", saved.fromEntityName);
        assertEquals("Target", saved.toEntityName);
        assertInstanceOf(BigDecimal.class, jdbc.insertArguments[3]);
        assertInstanceOf(LocalDate.class, jdbc.insertArguments[5]);
        assertInstanceOf(OffsetDateTime.class, jdbc.insertArguments[9]);
        assertInstanceOf(OffsetDateTime.class, jdbc.insertArguments[10]);
    }

    @Test
    void accessibleHistoryIsScopedInSqlAndDeduplicatesIds() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        JdbcEntityTransferRepository repository = new JdbcEntityTransferRepository(jdbc);

        List<EntityTransfer> transfers = repository.findAccessible(List.of(3L, 7L, 3L), 7L);

        assertEquals(1, transfers.size());
        assertTrue(jdbc.sql.contains("transfer.from_entity_id IN (?, ?)"));
        assertTrue(jdbc.sql.contains("transfer.to_entity_id IN (?, ?)"));
        assertArrayEquals(new Object[] {3L, 7L, 3L, 7L, 7L, 7L}, jdbc.queryArguments);
    }

    @Test
    void emptyAccessSetDoesNotQueryTheDatabase() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        JdbcEntityTransferRepository repository = new JdbcEntityTransferRepository(jdbc);

        assertTrue(repository.findAccessible(List.of(), null).isEmpty());
        assertEquals(0, jdbc.queryCount);
    }

    @Test
    void pairExistenceIsDirectionIndependent() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        JdbcEntityTransferRepository repository = new JdbcEntityTransferRepository(jdbc);

        assertTrue(repository.existsBetween(3, 7));

        assertArrayEquals(new Object[] {3L, 7L, 7L, 3L}, jdbc.existsArguments);
    }

    private EntityTransfer transfer() {
        EntityTransfer transfer = new EntityTransfer();
        transfer.fromEntityId = 3;
        transfer.toEntityId = 7;
        transfer.transferType = " SHAREHOLDER_ADVANCE ";
        transfer.amount = new BigDecimal("125.5000");
        transfer.currency = " cny ";
        transfer.transferDate = "2026-09-04";
        transfer.note = " working capital ";
        transfer.status = " RECORDED ";
        transfer.operatorUserId = 9;
        return transfer;
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private Object[] insertArguments;
        private Object[] existsArguments;
        private Object[] queryArguments;
        private String sql;
        private int queryCount;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            this.sql = sql;
            if (requiredType == Boolean.class) {
                existsArguments = args;
                return (T) Boolean.TRUE;
            }
            insertArguments = args;
            return (T) Long.valueOf(41);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.queryArguments = args;
            queryCount += 1;
            EntityTransfer transfer = new EntityTransfer();
            transfer.fromEntityName = "Source";
            transfer.toEntityName = "Target";
            return (List<T>) List.of(transfer);
        }

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            this.sql = sql;
            this.queryArguments = args;
            return Map.of("from_entity_name", "Source", "to_entity_name", "Target");
        }
    }
}
