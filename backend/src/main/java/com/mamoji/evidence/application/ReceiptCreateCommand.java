package com.mamoji.evidence.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Transport-independent command for creating receipt evidence. */
public record ReceiptCreateCommand(
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
    String accountingStatus,
    String accountingVoucherNo,
    String accountingEntry,
    String businessPurpose,
    String expenseOwner,
    LocalDate issueDate,
    LocalDate dueDate,
    String status,
    String fileName,
    Long fileSize,
    String fileType,
    String note
) {
}
