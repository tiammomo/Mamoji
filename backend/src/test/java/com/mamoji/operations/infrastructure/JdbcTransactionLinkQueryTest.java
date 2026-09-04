package com.mamoji.operations.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mamoji.operations.application.TransactionLinkTarget;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcTransactionLinkQueryTest {
    @Test
    @SuppressWarnings("unchecked")
    void readsOnlyTheTransactionIdentityNeededForLinking() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionLinkTarget target = new TransactionLinkTarget(42L, 7L, 9L);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(42L))).thenReturn(List.of(target));
        JdbcTransactionLinkQuery query = new JdbcTransactionLinkQuery(jdbc);

        assertEquals(target, query.findById(42L).orElseThrow());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(42L));
        assertTrue(sql.getValue().contains("SELECT id, company_id, user_id"));
        assertFalse(sql.getValue().contains("JOIN"));
        assertFalse(sql.getValue().contains("SELECT *"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsEmptyWhenTheTransactionDoesNotExist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(404L))).thenReturn(List.of());
        JdbcTransactionLinkQuery query = new JdbcTransactionLinkQuery(jdbc);

        assertTrue(query.findById(404L).isEmpty());
    }
}
