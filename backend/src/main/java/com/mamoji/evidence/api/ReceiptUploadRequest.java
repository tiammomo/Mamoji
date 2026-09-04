package com.mamoji.evidence.api;

import com.mamoji.evidence.application.ReceiptUploadCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/** Validated multipart metadata shared by single and batch receipt uploads. */
public record ReceiptUploadRequest(
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
    @Size(max = 1000) String businessPurpose,
    @Size(max = 160) String expenseOwner,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issueDate,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
    @Pattern(regexp = "pending_review|verified|linked|archived|rejected") String status,
    @Size(max = 2000) String note
) {
    public ReceiptUploadCommand toCommand() {
        return new ReceiptUploadCommand(
            companyId, transactionId, voucherNo, title, voucherType, direction, counterparty,
            amount, taxAmount, taxRate, taxPeriod, invoiceCheckStatus, deductionStatus,
            reimbursementStatus, businessPurpose, expenseOwner, issueDate, dueDate, status, note
        );
    }
}
