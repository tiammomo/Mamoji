package com.mamoji.accountingperiod.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Current company-level accounting close watermark and its latest administrative action. */
public record AccountingPeriodControl(
    long companyId,
    long version,
    LocalDate closedThrough,
    String lastAction,
    OffsetDateTime lastActionAt,
    Long lastActionBy,
    String lastActionReason,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public boolean closes(LocalDate transactionDate) {
        return closedThrough != null && !transactionDate.isAfter(closedThrough);
    }
}
