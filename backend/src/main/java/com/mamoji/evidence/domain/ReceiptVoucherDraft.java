package com.mamoji.evidence.domain;

import java.math.BigDecimal;

/** Strongly typed values required to create a receipt voucher. */
public record ReceiptVoucherDraft(
    long companyId,
    Long transactionId,
    String voucherNo,
    String title,
    String voucherType,
    String direction,
    String counterparty,
    BigDecimal amount,
    BigDecimal taxAmount,
    String issueDate,
    String dueDate,
    String status,
    String fileName,
    long fileSize,
    String fileType,
    String riskLevel,
    String note,
    long operatorUserId
) {
}
