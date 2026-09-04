package com.mamoji.evidence.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcReceiptFileHashRepositoryTest {
    @Test
    void locksTheCompanyHashInsideTheCurrentTransaction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcReceiptFileHashRepository repository = new JdbcReceiptFileHashRepository(jdbc);

        repository.lock(7L, "abc123");

        verify(jdbc).query(
            anyString(),
            (org.springframework.jdbc.core.RowCallbackHandler) org.mockito.ArgumentMatchers.any(),
            eq("receipt-file:7:abc123")
        );
    }

    @Test
    void findsACompanyScopedDuplicateVoucher() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(7L), eq("abc123")))
            .thenReturn(List.of(42L));
        JdbcReceiptFileHashRepository repository = new JdbcReceiptFileHashRepository(jdbc);

        assertEquals(42L, repository.findVoucherId(7L, "abc123").orElseThrow());
    }

    @Test
    void returnsEmptyWhenTheAttachmentHashIsNew() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(7L), eq("new-hash")))
            .thenReturn(List.of());
        JdbcReceiptFileHashRepository repository = new JdbcReceiptFileHashRepository(jdbc);

        assertTrue(repository.findVoucherId(7L, "new-hash").isEmpty());
    }

    @Test
    void registersTheVoucherHash() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcReceiptFileHashRepository repository = new JdbcReceiptFileHashRepository(jdbc);

        repository.register(7L, 42L, "abc123", "receipt.pdf", 128L, "2026-09-05T12:00:00Z");

        verify(jdbc).update(
            anyString(),
            eq(7L),
            eq(42L),
            eq("abc123"),
            eq("receipt.pdf"),
            eq(128L),
            eq("2026-09-05T12:00:00Z")
        );
    }
}
