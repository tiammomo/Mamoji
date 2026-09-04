package com.mamoji.evidence.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mamoji.domain.Models.ReceiptVoucher;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcReceiptVoucherRepositoryTest {
    @Test
    void returnsDatabaseCount() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM receipt_vouchers", Long.class)).thenReturn(7L);
        JdbcReceiptVoucherRepository repository = new JdbcReceiptVoucherRepository(jdbc);

        assertEquals(7, repository.count());
    }

    @Test
    void rejectsAStaleVoucherUpdate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcReceiptVoucherRepository repository = new JdbcReceiptVoucherRepository(jdbc);
        ReceiptVoucher voucher = new ReceiptVoucher();
        voucher.id = 42;
        voucher.version = 3;

        OptimisticLockingFailureException exception = assertThrows(
            OptimisticLockingFailureException.class,
            () -> repository.save(voucher)
        );

        assertEquals("Receipt voucher was changed by another request: 42", exception.getMessage());
        assertEquals(3, voucher.version);
    }

    @Test
    void advancesVersionAfterAStoredUpdate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcReceiptVoucherRepository repository = new JdbcReceiptVoucherRepository(jdbc);
        ReceiptVoucher voucher = new ReceiptVoucher();
        voucher.id = 42;
        voucher.version = 3;

        repository.save(voucher);

        assertEquals(4, voucher.version);
    }
}
