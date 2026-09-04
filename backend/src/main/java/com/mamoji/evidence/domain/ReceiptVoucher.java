package com.mamoji.evidence.domain;

import java.math.BigDecimal;

/** Receipt aggregate persisted and owned by the evidence module. */
public class ReceiptVoucher {
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
