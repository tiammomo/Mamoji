package com.mamoji.operations.domain;

import java.math.BigDecimal;

/** Operations-owned accounting transaction state and relation projection. */
public class TransactionRecord {
    public long id;
    public long version;
    public String idempotencyKey;
    public Long companyId;
    public long userId;
    public Long familyId;
    public int type;
    public BigDecimal amount;
    public long categoryId;
    public String categoryName;
    public String categoryIcon;
    public String categoryColor;
    public long accountId;
    public String accountName;
    public String date;
    public String note;
    public Long originalTransactionId;
    public BigDecimal refundedAmount;
    public boolean isRefundable;
    public Long budgetId;
    public String createdAt;
    public String updatedAt;
}
