package com.mamoji.domain;

import java.math.BigDecimal;
import java.util.List;

public final class Models {
    private Models() {
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
