package com.mamoji.finance.domain;

import java.math.BigDecimal;

/** Finance-owned account state and read metrics. */
public class Account {
    public long id;
    public long version;
    public Long companyId;
    public String name;
    public String type;
    public String subType;
    public String bank;
    public String accountNo;
    public String openingBank;
    public String currency;
    public BigDecimal balance;
    public BigDecimal availableBalance;
    public BigDecimal creditLimit;
    public BigDecimal frozenAmount;
    public boolean includeInNetWorth;
    public long userId;
    public Long ledgerId;
    public int status;
    public String openedAt;
    public String lastReconciledAt;
    public String ownerName;
    public String purpose;
    public String reconciliationStatus;
    public String riskLevel;
    public BigDecimal monthlyIncome;
    public BigDecimal monthlyExpense;
    public BigDecimal currentMonthNetFlow;
    public long transactionCount;
    public String lastTransactionDate;
    public String createdAt;
    public String updatedAt;
}
