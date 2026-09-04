package com.mamoji.evidence.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mamoji.evidence.application.ReceiptStorageReferenceSnapshot;
import com.mamoji.evidence.domain.ReceiptObjectLocation;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcReceiptStorageReferenceRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void readsMinioReferencesAndFallsBackToTheCurrentBucket() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet current = row(" ", "receipts/current.pdf");
        ResultSet archive = row("archive", "receipts/archive.pdf");
        ResultSet invalid = row("archive", " ");
        when(jdbc.query(anyString(), any(RowMapper.class), eq(101))).thenAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(1);
            return List.of(
                mapper.mapRow(current, 0),
                mapper.mapRow(archive, 1),
                mapper.mapRow(invalid, 2)
            );
        });
        JdbcReceiptStorageReferenceRepository repository = new JdbcReceiptStorageReferenceRepository(jdbc);

        ReceiptStorageReferenceSnapshot snapshot = repository.findAll("mamoji", 100);

        assertEquals(List.of(
            new ReceiptObjectLocation("mamoji", "receipts/current.pdf"),
            new ReceiptObjectLocation("archive", "receipts/archive.pdf")
        ), snapshot.references());
        assertEquals(1, snapshot.invalidReferenceCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsInsteadOfReturningATruncatedReferenceInventory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(2))).thenReturn(Arrays.asList(null, null));
        JdbcReceiptStorageReferenceRepository repository = new JdbcReceiptStorageReferenceRepository(jdbc);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> repository.findAll("mamoji", 1)
        );

        assertTrue(exception.getMessage().contains("exceeded configured maximum"));
    }

    private ResultSet row(String bucket, String objectKey) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("file_bucket")).thenReturn(bucket);
        when(resultSet.getString("file_object_key")).thenReturn(objectKey);
        return resultSet;
    }
}
