package com.mamoji.evidence.domain;

import java.math.BigDecimal;

/** Database-backed read projection for receipt amount and workflow counters. */
public record ReceiptSummary(
    long totalCount,
    BigDecimal totalAmount,
    BigDecimal salesInvoiceAmount,
    BigDecimal purchaseInvoiceAmount,
    BigDecimal outputTaxAmount,
    BigDecimal deductibleTaxAmount,
    BigDecimal reimbursementAmount,
    BigDecimal reimbursementPendingAmount,
    BigDecimal pendingAmount,
    long pendingReviewCount,
    long missingAttachmentCount,
    long missingTransactionCount,
    long highRiskCount,
    long uncheckedInvoiceCount,
    long pendingDeductionCount,
    long pendingReimbursementCount,
    long missingTaxPeriodCount,
    long pendingApprovalCount,
    long pendingAccountingCount,
    long postedAccountingCount
) {
}
