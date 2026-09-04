package com.mamoji.domain;

import java.math.BigDecimal;
import java.util.List;

public final class Models {
    private Models() {
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
        public long version;
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

}
