package com.mamoji.platform.tenant;

import java.math.BigDecimal;

/** Append-only record of funds moving between two tenant subjects. */
public class EntityTransfer {
    public long id;
    public long fromEntityId;
    public long toEntityId;
    public String fromEntityName;
    public String toEntityName;
    public String transferType;
    public BigDecimal amount;
    public String currency;
    public String transferDate;
    public String note;
    public String status;
    public long operatorUserId;
    public String createdAt;
    public String updatedAt;
}
