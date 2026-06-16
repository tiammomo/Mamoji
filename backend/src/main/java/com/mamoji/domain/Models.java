package com.mamoji.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.util.List;

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

    public static class RegistrationInvite {
        public long id;
        public String token;
        public String email;
        public int role;
        public int permissions;
        public String expiresAt;
        public String acceptedAt;
        public Long acceptedUserId;
        public long invitedByUserId;
        public String createdAt;
        public String updatedAt;
    }

    public static class Account {
        public long id;
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
        public String employeeNo;
        public String name;
        public String legalName;
        public String preferredName;
        public String email;
        public String phone;
        public String position;
        public Long directManagerEmployeeId;
        public String jobLevel;
        public String workLocation;
        public String employmentType;
        public String status;
        public String accessRole;
        public String accessScope;
        public String hireDate;
        public String leaveDate;
        public String probationStartDate;
        public String probationEndDate;
        public String contractStartDate;
        public String contractEndDate;
        public String contractType;
        public String contractStatus;
        public String educationLevel;
        public String graduationSchool;
        public String major;
        public String graduationDate;
        public Integer graduationYear;
        public String graduateStatus;
        public String skillTags;
        public String resumeSummary;
        public String materialStatus;
        public String profileVerifiedAt;
        public Long profileVerifiedBy;
        public BigDecimal salary;
        public BigDecimal overtimeBase;
        public BigDecimal weekdayOvertimeHours;
        public BigDecimal restDayOvertimeHours;
        public BigDecimal holidayOvertimeHours;
        public BigDecimal overtimePay;
        public String overtimePolicyNote;
        public BigDecimal socialInsurance;
        public BigDecimal housingFund;
        public BigDecimal taxEstimate;
        public BigDecimal monthlyCost;
        public BigDecimal socialInsuranceBase;
        public BigDecimal socialInsurancePersonalRate;
        public BigDecimal socialInsuranceCompanyRate;
        public BigDecimal socialInsurancePersonalAmount;
        public BigDecimal socialInsuranceCompanyAmount;
        public BigDecimal housingFundBase;
        public BigDecimal housingFundPersonalRate;
        public BigDecimal housingFundCompanyRate;
        public BigDecimal housingFundPersonalAmount;
        public BigDecimal housingFundCompanyAmount;
        public BigDecimal personalDeduction;
        public BigDecimal netPayEstimate;
        public String socialInsuranceRegion;
        public String hukouType;
        public String medicalTier;
        public BigDecimal pensionBase;
        public BigDecimal medicalBase;
        public BigDecimal unemploymentBase;
        public BigDecimal workInjuryBase;
        public BigDecimal maternityBase;
        public BigDecimal workInjuryCompanyRate;
        public String socialInsurancePolicyNote;
        public List<SocialInsuranceItem> socialInsuranceItems;
        public List<String> socialInsuranceWarnings;
        public List<EmployeeCertificate> certificates;
        public List<EmployeeExperience> experiences;
        public String emergencyContact;
        public String createdAt;
        public String updatedAt;
    }

    public static class EmployeeCertificate {
        public long id;
        public long employeeId;
        public String name;
        public String category;
        public String level;
        public String issuer;
        public String certificateNo;
        public String issueDate;
        public String expiryDate;
        public String verificationStatus;
        public String materialStatus;
        public String note;
        public String createdAt;
        public String updatedAt;
    }

    public static class EmployeeExperience {
        public long id;
        public long employeeId;
        public String type;
        public String organization;
        public String title;
        public String startDate;
        public String endDate;
        public String description;
        public String achievements;
        public String skills;
        public String createdAt;
        public String updatedAt;
    }

    public static class SocialInsuranceItem {
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

    public static class ReceiptVoucher {
        public long id;
        public long companyId;
        public Long transactionId;
        public String voucherNo;
        public String title;
        public String voucherType;
        public String direction;
        public String counterparty;
        public BigDecimal amount;
        public BigDecimal taxAmount;
        public BigDecimal taxRate;
        public String taxPeriod;
        public String invoiceCheckStatus;
        public String deductionStatus;
        public String reimbursementStatus;
        public String approvalStatus;
        public String accountingStatus;
        public String accountingVoucherNo;
        public String accountingEntry;
        public Long approvedByUserId;
        public String approvedAt;
        public String accountedAt;
        public String businessPurpose;
        public String expenseOwner;
        public String issueDate;
        public String dueDate;
        public String status;
        public String fileName;
        public long fileSize;
        public String fileType;
        public String fileStorageProvider;
        public String fileBucket;
        public String fileObjectKey;
        public String fileUrl;
        public String riskLevel;
        public String note;
        public long operatorUserId;
        public String createdAt;
        public String updatedAt;
    }

    public static class PayrollRun {
        public long id;
        public long companyId;
        public String period;
        public String name;
        public String status;
        public int employeeCount;
        public BigDecimal salaryTotal;
        public BigDecimal socialPersonalTotal;
        public BigDecimal socialCompanyTotal;
        public BigDecimal housingPersonalTotal;
        public BigDecimal housingCompanyTotal;
        public BigDecimal taxTotal;
        public BigDecimal personalDeductionTotal;
        public BigDecimal netPayTotal;
        public BigDecimal companyCostTotal;
        public long createdByUserId;
        public Long closedByUserId;
        public String closedAt;
        public String createdAt;
        public String updatedAt;
        public List<PayrollRunItem> items;
    }

    public static class PayrollRunItem {
        public long id;
        public long runId;
        public long companyId;
        public long employeeId;
        public String employeeName;
        public String departmentName;
        public String period;
        public BigDecimal salary;
        public BigDecimal payableSalary;
        public BigDecimal socialPersonalAmount;
        public BigDecimal socialCompanyAmount;
        public BigDecimal housingPersonalAmount;
        public BigDecimal housingCompanyAmount;
        public BigDecimal taxAmount;
        public BigDecimal personalDeduction;
        public BigDecimal netPay;
        public BigDecimal companyCost;
        public String snapshotJson;
        public String createdAt;
    }

    public static class AuditLog {
        public long id;
        public long companyId;
        public String entityType;
        public long entityId;
        public String action;
        public String summary;
        public long actorUserId;
        public String actorName;
        public String createdAt;
    }

    public static class OutboxEvent {
        public long id;
        public String eventId;
        public String eventType;
        public String aggregateType;
        public long aggregateId;
        public long companyId;
        public long actorUserId;
        public String payloadJson;
        public String status;
        public int attempts;
        public String nextAttemptAt;
        public String lockedAt;
        public String processedAt;
        public String lastError;
        public String createdAt;
        public String updatedAt;
    }
}
