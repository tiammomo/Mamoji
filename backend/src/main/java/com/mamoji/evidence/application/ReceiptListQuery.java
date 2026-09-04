package com.mamoji.evidence.application;

import com.mamoji.common.PageRequest;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Transport-independent receipt filters and pagination requested by a caller. */
public record ReceiptListQuery(
    Long companyId,
    String keyword,
    String voucherType,
    String direction,
    String status,
    String invoiceCheckStatus,
    String deductionStatus,
    String reimbursementStatus,
    String taxPeriod,
    String linkState,
    LocalDate startDate,
    LocalDate endDate,
    BigDecimal minAmount,
    BigDecimal maxAmount,
    int page,
    int size
) {
    public ReceiptListQuery {
        keyword = keyword == null ? "" : keyword.trim();
        voucherType = nullIfBlank(voucherType);
        direction = nullIfBlank(direction);
        status = nullIfBlank(status);
        invoiceCheckStatus = nullIfBlank(invoiceCheckStatus);
        deductionStatus = nullIfBlank(deductionStatus);
        reimbursementStatus = nullIfBlank(reimbursementStatus);
        taxPeriod = nullIfBlank(taxPeriod);
        linkState = nullIfBlank(linkState);
    }

    public PageRequest pageRequest() {
        return new PageRequest(page, size);
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
