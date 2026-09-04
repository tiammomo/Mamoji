package com.mamoji.evidence.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Transport-independent metadata command for receipt attachment uploads. */
public record ReceiptUploadCommand(
    Long companyId,
    Long transactionId,
    String voucherNo,
    String title,
    String voucherType,
    String direction,
    String counterparty,
    BigDecimal amount,
    BigDecimal taxAmount,
    BigDecimal taxRate,
    String taxPeriod,
    String invoiceCheckStatus,
    String deductionStatus,
    String reimbursementStatus,
    String businessPurpose,
    String expenseOwner,
    LocalDate issueDate,
    LocalDate dueDate,
    String status,
    String note
) {
}
