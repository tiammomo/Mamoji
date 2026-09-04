package com.mamoji.people.domain;

import java.math.BigDecimal;

/** Calculated social-insurance line exposed with an employee compensation view. */
public class SocialInsuranceItem {
    public String key;
    public String name;
    public String category;
    public BigDecimal base;
    public BigDecimal minBase;
    public BigDecimal maxBase;
    public BigDecimal personalRate;
    public BigDecimal companyRate;
    public BigDecimal personalAmount;
    public BigDecimal companyAmount;
    public String policyBasis;
    public String validPeriod;
    public String status;
}
