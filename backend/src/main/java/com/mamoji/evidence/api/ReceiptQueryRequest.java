package com.mamoji.evidence.api;

import com.mamoji.common.PageRequest;
import com.mamoji.evidence.application.ReceiptListQuery;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/** Validated query-string contract for receipt searches and database pagination. */
public record ReceiptQueryRequest(
    @Positive Long companyId,
    @Size(max = 200) String keyword,
    @Pattern(regexp = "(?:|sales_invoice|purchase_invoice|receipt|bank_slip|contract|reimbursement|tax_receipt)") String voucherType,
    @Pattern(regexp = "(?:|income|expense)") String direction,
    @Pattern(regexp = "(?:|pending_review|verified|linked|archived|rejected)") String status,
    @Pattern(regexp = "(?:|not_required|pending|verified|failed)") String invoiceCheckStatus,
    @Pattern(regexp = "(?:|not_applicable|pending|deductible|deducted|transferred_out)") String deductionStatus,
    @Pattern(regexp = "(?:|not_applicable|submitted|approved|paid|archived|rejected)") String reimbursementStatus,
    @Pattern(regexp = "(?:|\\d{4}-(0[1-9]|1[0-2]))") String taxPeriod,
    @Pattern(regexp = "(?:|linked|missing)") String linkState,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
    @DecimalMin("0") @Digits(integer = 16, fraction = 4) BigDecimal minAmount,
    @DecimalMin("0") @Digits(integer = 16, fraction = 4) BigDecimal maxAmount,
    @Min(0) Integer page,
    @Min(1) @Max(PageRequest.MAX_SIZE) Integer size
) {
    public ReceiptListQuery toQuery() {
        return new ReceiptListQuery(
            companyId,
            keyword,
            voucherType,
            direction,
            status,
            invoiceCheckStatus,
            deductionStatus,
            reimbursementStatus,
            taxPeriod,
            linkState,
            startDate,
            endDate,
            minAmount,
            maxAmount,
            page == null ? PageRequest.DEFAULT_PAGE : page,
            size == null ? PageRequest.DEFAULT_SIZE : size
        );
    }
}
