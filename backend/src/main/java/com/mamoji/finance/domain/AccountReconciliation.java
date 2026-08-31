package com.mamoji.finance.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;

/** Immutable snapshot comparing an external statement with the system account balance. */
public record AccountReconciliation(
    long id,
    long companyId,
    @JsonIgnore
    long userId,
    long accountId,
    String statementDate,
    BigDecimal statementBalance,
    BigDecimal systemBalance,
    BigDecimal difference,
    String status,
    String note,
    long createdBy,
    String createdAt
) {
}
