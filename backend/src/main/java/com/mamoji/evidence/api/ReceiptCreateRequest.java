package com.mamoji.evidence.api;

import com.mamoji.evidence.application.ReceiptCreateCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Validated JSON contract for creating a receipt without an attachment upload. */
public record ReceiptCreateRequest(
    @Positive Long companyId,
    @PositiveOrZero Long transactionId,
    @Size(max = 120) @Pattern(regexp = "(?s).*\\S.*") String voucherNo,
    @Size(max = 200) @Pattern(regexp = "(?s).*\\S.*") String title,
    @Pattern(regexp = "sales_invoice|purchase_invoice|receipt|bank_slip|contract|reimbursement|tax_receipt") String voucherType,
    @Pattern(regexp = "income|expense") String direction,
    @Size(max = 200) @Pattern(regexp = "(?s).*\\S.*") String counterparty,
    @DecimalMin("0") @Digits(integer = 16, fraction = 4) BigDecimal amount,
    @DecimalMin("0") @Digits(integer = 16, fraction = 4) BigDecimal taxAmount,
    @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal taxRate,
    @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String taxPeriod,
    @Pattern(regexp = "not_required|pending|verified|failed") String invoiceCheckStatus,
    @Pattern(regexp = "not_applicable|pending|deductible|deducted|transferred_out") String deductionStatus,
    @Pattern(regexp = "not_applicable|submitted|approved|paid|archived|rejected") String reimbursementStatus,
    @Null(message = "must be changed through the approval workflow") String approvalStatus,
    @Pattern(regexp = "not_started|draft|posted|reversed") String accountingStatus,
    @Size(max = 120) String accountingVoucherNo,
    @Size(max = 4000) String accountingEntry,
    @Size(max = 1000) String businessPurpose,
    @Size(max = 160) String expenseOwner,
    LocalDate issueDate,
    LocalDate dueDate,
    @Pattern(regexp = "pending_review|verified|linked|archived|rejected") String status,
    @Size(max = 255) String fileName,
    @PositiveOrZero @Max(Integer.MAX_VALUE) Long fileSize,
    @Size(max = 128) String fileType,
    @Size(max = 2000) String note
) {
    public ReceiptCreateCommand toCommand() {
        return new ReceiptCreateCommand(
            companyId, transactionId, voucherNo, title, voucherType, direction, counterparty,
            amount, taxAmount, taxRate, taxPeriod, invoiceCheckStatus, deductionStatus,
            reimbursementStatus, accountingStatus, accountingVoucherNo, accountingEntry,
            businessPurpose, expenseOwner, issueDate, dueDate, status, fileName, fileSize, fileType, note
        );
    }
}
