package com.mamoji.evidence.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mamoji.evidence.domain.ReceiptStorageUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcReceiptStorageUsageRepositoryTest {
    @Test
    void locksTheCompanyCapacityInsideTheCurrentTransaction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcReceiptStorageUsageRepository repository = new JdbcReceiptStorageUsageRepository(jdbc);

        repository.lockCompany(7L);

        verify(jdbc).query(
            anyString(),
            (org.springframework.jdbc.core.RowCallbackHandler) any(),
            eq("receipt-storage:7")
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void sumsOnlyCommittedMinioReceiptMetadataForTheCompany() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReceiptStorageUsage expected = new ReceiptStorageUsage(3L, 4_096L);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(7L))).thenReturn(expected);
        JdbcReceiptStorageUsageRepository repository = new JdbcReceiptStorageUsageRepository(jdbc);

        assertEquals(expected, repository.findByCompany(7L));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), any(RowMapper.class), eq(7L));
        assertTrue(sql.getValue().contains("voucher.company_id = ?"));
        assertTrue(sql.getValue().contains("voucher.file_storage_provider = 'minio'"));
        assertTrue(sql.getValue().contains("receipt_file_hashes"));
        assertTrue(sql.getValue().contains("MAX(file_hash.file_size)"));
        assertTrue(sql.getValue().contains("voucher.file_size"));
    }
}
