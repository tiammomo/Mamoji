package com.mamoji.evidence.infrastructure;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mamoji.domain.Models.ReceiptVoucher;
import com.mamoji.evidence.domain.ReceiptVoucherDraft;
import com.mamoji.repository.EnterpriseStore;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiptVoucherRepositoryTest {
    @Test
    void mapsTypedDraftToTheCompatibilityAdapter() {
        EnterpriseStore store = mock(EnterpriseStore.class);
        ReceiptVoucher expected = new ReceiptVoucher();
        when(store.receiptVoucher(
            9,
            17L,
            "RC-001",
            "Office supplies",
            "purchase_invoice",
            "expense",
            "Vendor",
            "12.30",
            "1.20",
            "2026-08-30",
            null,
            "pending_review",
            "receipt.pdf",
            128,
            "application/pdf",
            "low",
            "business evidence",
            3
        )).thenReturn(expected);
        ReceiptVoucherRepository repository = new ReceiptVoucherRepository(store);
        ReceiptVoucherDraft draft = new ReceiptVoucherDraft(
            9,
            17L,
            "RC-001",
            "Office supplies",
            "purchase_invoice",
            "expense",
            "Vendor",
            new BigDecimal("12.30"),
            new BigDecimal("1.20"),
            "2026-08-30",
            null,
            "pending_review",
            "receipt.pdf",
            128,
            "application/pdf",
            "low",
            "business evidence",
            3
        );

        ReceiptVoucher actual = repository.insert(draft);

        assertSame(expected, actual);
    }

    @Test
    void exposesCompanyScopedReadsThroughTheEvidenceBoundary() {
        EnterpriseStore store = mock(EnterpriseStore.class);
        ReceiptVoucher voucher = new ReceiptVoucher();
        List<ReceiptVoucher> expected = List.of(voucher);
        when(store.sortedReceiptVouchers(9)).thenReturn(expected);
        ReceiptVoucherRepository repository = new ReceiptVoucherRepository(store);

        List<ReceiptVoucher> actual = repository.findByCompany(9);

        assertSame(expected, actual);
        verify(store).sortedReceiptVouchers(9);
    }
}
