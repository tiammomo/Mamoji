package com.mamoji.platform.tenant;

/** Durable business subject used as the tenant boundary across enterprise modules. */
public class Company {
    public long id;
    public long version;
    public String name;
    public String entityType;
    public String creditCode;
    public String industry;
    public String taxpayerType;
    public String currency;
    public String country;
    public String province;
    public String city;
    public String district;
    public String registeredAddress;
    public String operatingRegion;
    public String taxAuthority;
    public String policyProfileKey;
    public int fiscalYearStartMonth;
    public long ownerId;
    public String createdAt;
    public String updatedAt;
}
