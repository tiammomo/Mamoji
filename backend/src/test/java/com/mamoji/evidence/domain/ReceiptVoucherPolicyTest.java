package com.mamoji.evidence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReceiptVoucherPolicyTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 31);

    @Test
    void initializesTaxApprovalAccountingAndFileDefaults() {
        ReceiptVoucher voucher = ReceiptVoucherPolicy.initialize(
            draft(new BigDecimal("6120"), new BigDecimal("120"), "purchase_invoice", "pending_review", "invoice.pdf"),
            TODAY,
            "2026-08-31T10:00:00+08:00"
        );

        assertEquals(new BigDecimal("2.00"), voucher.taxRate);
        assertEquals("2026-08", voucher.taxPeriod);
        assertEquals("pending", voucher.invoiceCheckStatus);
        assertEquals("pending", voucher.deductionStatus);
        assertEquals("not_submitted", voucher.approvalStatus);
        assertEquals("not_started", voucher.accountingStatus);
        assertEquals("借：管理费用 6000，应交税费-进项税额 120；贷：应付账款 6120", voucher.accountingEntry);
        assertEquals("metadata_only", voucher.fileStorageProvider);
        assertNull(voucher.accountingVoucherNo);
    }

    @Test
    void hydratesLegacyClosedInvoiceAndCreatesAccountingVoucherNumber() {
        ReceiptVoucher voucher = new ReceiptVoucher();
        voucher.id = 23;
        voucher.amount = new BigDecimal("300");
        voucher.taxAmount = new BigDecimal("0");
        voucher.voucherType = "purchase_invoice";
        voucher.direction = "expense";
        voucher.issueDate = "2026-07-12";
        voucher.status = "archived";
        voucher.invoiceCheckStatus = "not_required";
        voucher.deductionStatus = "not_applicable";
        voucher.fileName = "legacy.pdf";
        voucher.updatedAt = "2026-07-13T09:00:00+08:00";

        boolean changed = ReceiptVoucherPolicy.hydrate(voucher, TODAY);

        assertTrue(changed);
        assertEquals("verified", voucher.invoiceCheckStatus);
        assertEquals("deductible", voucher.deductionStatus);
        assertEquals("posted", voucher.accountingStatus);
        assertEquals("JV-202607-0023", voucher.accountingVoucherNo);
        assertEquals(voucher.updatedAt, voucher.accountedAt);
        assertEquals("metadata_only", voucher.fileStorageProvider);
    }

    private static ReceiptVoucherDraft draft(
        BigDecimal amount,
        BigDecimal taxAmount,
        String voucherType,
        String status,
        String fileName
    ) {
        return new ReceiptVoucherDraft(
            9,
            null,
            "RC-001",
            "Office supplies",
            voucherType,
            "expense",
            "Vendor",
            amount,
            taxAmount,
            "2026-08-30",
            null,
            status,
            fileName,
            128,
            "application/pdf",
            "low",
            "business evidence",
            3
        );
    }
}
