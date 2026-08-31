package com.mamoji.recurring.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;

/** Persisted recurring income or expense rule and its execution cursor. */
public class RecurringItem {
    public String id;
    public Long companyId;
    public String name;
    public int type;
    public BigDecimal amount;
    public String frequency;
    public int interval;
    public Integer dayOfWeek;
    public Integer dayOfMonth;
    public Integer monthOfYear;
    public String startDate;
    public String endDate;
    public String lastExecuted;
    public String nextExecution;
    public int status;
    public int executionCount;
    public String note;

    @JsonIgnore
    public long userId;
}
