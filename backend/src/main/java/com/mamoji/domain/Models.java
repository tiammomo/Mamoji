package com.mamoji.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;

public final class Models {
    private Models() {
    }

    public static class User {
        public long id;
        public String email;
        public String nickname;
        public String avatar;
        public Long familyId;
        public int role;
        public int permissions;
        public String createdAt;
        public String updatedAt;

        @JsonIgnore
        public String passwordHash;
    }

    public static class Account {
        public long id;
        public String name;
        public String type;
        public String subType;
        public String bank;
        public BigDecimal balance;
        public boolean includeInNetWorth;
        public long userId;
        public Long ledgerId;
        public int status;
        public String createdAt;
        public String updatedAt;
    }

    public static class Category {
        public long id;
        public String name;
        public String icon;
        public String color;
        public String type;
        public long userId;
        public int status;
        public String createdAt;
        public String updatedAt;
    }

    public static class Budget {
        public long id;
        public String name;
        public BigDecimal amount;
        public String startDate;
        public String endDate;
        public int warningThreshold;
        public int status;
        public BigDecimal spent;
        public BigDecimal remainingAmount;
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

    public static class TransactionRecord {
        public long id;
        public long userId;
        public Long familyId;
        public int type;
        public BigDecimal amount;
        public long categoryId;
        public String categoryName;
        public String categoryIcon;
        public String categoryColor;
        public long accountId;
        public String accountName;
        public String date;
        public String note;
        public Long originalTransactionId;
        public BigDecimal refundedAmount;
        public boolean isRefundable;
        public Long budgetId;
        public String createdAt;
        public String updatedAt;
    }

    public static class Ledger {
        public long id;
        public String name;
        public String description;
        public String currency;
        public long ownerId;
        public boolean isDefault;
        public int status;
        public String createdAt;
        public String updatedAt;
    }

    public static class LedgerMember {
        public long id;
        public long ledgerId;
        public long userId;
        public String role;
        public String nickname;
        public String avatar;
        public String joinedAt;
    }

    public static class RecurringItem {
        public String id;
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

    public static class Company {
        public long id;
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

    public static class Department {
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

    public static class Employee {
        public long id;
        public long companyId;
        public Long userId;
        public Long departmentId;
        public String departmentName;
        public String name;
        public String email;
        public String phone;
        public String position;
        public String employmentType;
        public String status;
        public String accessRole;
        public String accessScope;
        public String hireDate;
        public String leaveDate;
        public BigDecimal salary;
        public BigDecimal socialInsurance;
        public BigDecimal housingFund;
        public BigDecimal taxEstimate;
        public BigDecimal monthlyCost;
        public String emergencyContact;
        public String createdAt;
        public String updatedAt;
    }

    public static class EmploymentEvent {
        public long id;
        public long companyId;
        public long employeeId;
        public String type;
        public String effectiveDate;
        public String note;
        public long operatorUserId;
        public String createdAt;
    }

    public static class TaxItem {
        public long id;
        public long companyId;
        public String name;
        public String period;
        public String taxType;
        public BigDecimal taxableAmount;
        public BigDecimal taxAmount;
        public BigDecimal paidAmount;
        public String dueDate;
        public String status;
        public String note;
        public String createdAt;
        public String updatedAt;
    }

    public static class EntityTransfer {
        public long id;
        public long fromEntityId;
        public long toEntityId;
        public String fromEntityName;
        public String toEntityName;
        public String transferType;
        public BigDecimal amount;
        public String currency;
        public String transferDate;
        public String note;
        public String status;
        public long operatorUserId;
        public String createdAt;
        public String updatedAt;
    }
}
