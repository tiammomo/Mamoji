package com.mamoji.evidence.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Transport-independent partial update command with explicit-null presence markers. */
public record ReceiptUpdateCommand(
    long expectedVersion,
    Long transactionId,
    boolean transactionIdPresent,
    String voucherNo,
    String title,
    String voucherType,
    String direction,
    String counterparty,
    BigDecimal amount,
    BigDecimal taxAmount,
    BigDecimal taxRate,
    String taxPeriod,
    boolean taxPeriodPresent,
    String invoiceCheckStatus,
    String deductionStatus,
    String reimbursementStatus,
    String accountingStatus,
    String accountingVoucherNo,
    boolean accountingVoucherNoPresent,
    String accountingEntry,
    boolean accountingEntryPresent,
    String businessPurpose,
    boolean businessPurposePresent,
    String expenseOwner,
    boolean expenseOwnerPresent,
    LocalDate issueDate,
    LocalDate dueDate,
    boolean dueDatePresent,
    String status,
    String fileName,
    boolean fileNamePresent,
    Long fileSize,
    String fileType,
    boolean fileTypePresent,
    String note,
    boolean notePresent
) {
}
