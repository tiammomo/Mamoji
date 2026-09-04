package com.mamoji.evidence.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mamoji.evidence.application.ReceiptFileRegistration;
import com.mamoji.evidence.domain.ReceiptFileDigest;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcReceiptFileHashRepositoryTest {
    private static final ReceiptFileDigest DIGEST = new ReceiptFileDigest("a".repeat(64));

    @Test
    void locksTheCompanyHashInsideTheCurrentTransaction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcReceiptFileHashRepository repository = new JdbcReceiptFileHashRepository(jdbc);

        repository.lock(7L, DIGEST);

        verify(jdbc).query(
            anyString(),
            (org.springframework.jdbc.core.RowCallbackHandler) org.mockito.ArgumentMatchers.any(),
            eq("receipt-file:7:" + DIGEST.value())
        );
    }

    @Test
    void findsACompanyScopedDuplicateVoucher() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(7L), eq(DIGEST.value())))
            .thenReturn(List.of(42L));
        JdbcReceiptFileHashRepository repository = new JdbcReceiptFileHashRepository(jdbc);

        assertEquals(42L, repository.findVoucherId(7L, DIGEST).orElseThrow());
    }

    @Test
    void returnsEmptyWhenTheAttachmentHashIsNew() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(7L), eq(DIGEST.value())))
            .thenReturn(List.of());
        JdbcReceiptFileHashRepository repository = new JdbcReceiptFileHashRepository(jdbc);

        assertTrue(repository.findVoucherId(7L, DIGEST).isEmpty());
    }

    @Test
    void registersTheVoucherHash() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcReceiptFileHashRepository repository = new JdbcReceiptFileHashRepository(jdbc);

        OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-05T12:00:00Z");
        repository.register(new ReceiptFileRegistration(7L, 42L, DIGEST, "receipt.pdf", 128L, createdAt));

        verify(jdbc).update(
            anyString(),
            eq(7L),
            eq(42L),
            eq(DIGEST.value()),
            eq("receipt.pdf"),
            eq(128L),
            eq(createdAt)
        );
    }
}
