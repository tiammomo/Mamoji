package com.mamoji.people.domain;

/** Append-only record of a company-scoped employee lifecycle change. */
public class EmploymentEvent {
    public long id;
    public long companyId;
    public long employeeId;
    public String type;
    public String effectiveDate;
    public String note;
    public long operatorUserId;
    public String createdAt;
}
