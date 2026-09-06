package com.mamoji.accountingperiod.domain;

import java.time.LocalDate;

/** Raised before a financial mutation attempts to change an already closed period. */
public final class AccountingPeriodClosedException extends RuntimeException {
    private final long companyId;
    private final LocalDate transactionDate;
    private final LocalDate closedThrough;

    public AccountingPeriodClosedException(long companyId, LocalDate transactionDate, LocalDate closedThrough) {
        super("Accounting period is closed through " + closedThrough + " for transaction date " + transactionDate);
        this.companyId = companyId;
        this.transactionDate = transactionDate;
        this.closedThrough = closedThrough;
    }

    public long companyId() {
        return companyId;
    }

    public LocalDate transactionDate() {
        return transactionDate;
    }

    public LocalDate closedThrough() {
        return closedThrough;
    }
}
