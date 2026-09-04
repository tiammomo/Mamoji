package com.mamoji.tax.domain;

import java.math.BigDecimal;

/** Company-scoped tax filing and payment work item. */
public class TaxItem {
    public long id;
    public long companyId;
    public String name;
    public String period;
    public String taxType;
    public BigDecimal taxableAmount;
    public BigDecimal taxAmount;
    public BigDecimal paidAmount;
    public BigDecimal deductibleAmount;
    public BigDecimal taxRate;
    public String dueDate;
    public String status;
    public String filingStatus;
    public String paymentStatus;
    public String frequency;
    public String declarationDate;
    public String paymentDate;
    public String responsiblePerson;
    public String riskLevel;
    public String policyBasis;
    public String sourceType;
    public String note;
    public String createdAt;
    public String updatedAt;
}
