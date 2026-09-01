package com.mamoji.finance.domain;

/** Finance-owned ledger aggregate state. */
public class Ledger {
    public long id;
    public long companyId;
    public String name;
    public String description;
    public String currency;
    public long ownerId;
    public boolean isDefault;
    public int status;
    public String createdAt;
    public String updatedAt;
}
