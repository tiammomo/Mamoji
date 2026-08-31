package com.mamoji.budget.domain;

import java.math.BigDecimal;

/** Budget-owned capacity, usage, and category projection. */
public class Budget {
    public long id;
    public long version;
    public Long companyId;
    public String name;
    public BigDecimal amount;
    public String startDate;
    public String endDate;
    public int warningThreshold;
    public int status;
    public BigDecimal spent;
    public BigDecimal reservedAmount;
    public BigDecimal remainingAmount;
    public BigDecimal availableAmount;
    public double usageRate;
    public boolean warningReached;
    public String riskLevel;
    public String riskMessage;
    public long userId;
    public Long ledgerId;
    public Long categoryId;
    public String categoryName;
    public String categoryIcon;
    public String createdAt;
    public String updatedAt;
}
