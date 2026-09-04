package com.mamoji.people.domain;

import java.math.BigDecimal;

/** Company-scoped organizational unit owned by People Core. */
public class Department {
    public long id;
    public long companyId;
    public String name;
    public String costCenter;
    public Long managerEmployeeId;
    public BigDecimal budget;
    public int status;
    public String createdAt;
    public String updatedAt;
}
