package com.mamoji.evidence.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.mamoji.evidence.application.ReceiptUpdateCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Partial receipt update contract; explicit null clears nullable fields. */
public class ReceiptUpdateRequest {
    @Size(max = 120)
    @Pattern(regexp = "(?s).*\\S.*")
    public String voucherNo;

    @Size(max = 200)
    @Pattern(regexp = "(?s).*\\S.*")
    public String title;

    @Pattern(regexp = "sales_invoice|purchase_invoice|receipt|bank_slip|contract|reimbursement|tax_receipt")
    public String voucherType;

    @Pattern(regexp = "income|expense")
    public String direction;

    @Size(max = 200)
    @Pattern(regexp = "(?s).*\\S.*")
    public String counterparty;

    @DecimalMin("0")
    @Digits(integer = 16, fraction = 4)
    public BigDecimal amount;

    @DecimalMin("0")
    @Digits(integer = 16, fraction = 4)
    public BigDecimal taxAmount;

    @DecimalMin("0")
    @DecimalMax("100")
    @Digits(integer = 3, fraction = 4)
    public BigDecimal taxRate;

    @Pattern(regexp = "not_required|pending|verified|failed")
    public String invoiceCheckStatus;

    @Pattern(regexp = "not_applicable|pending|deductible|deducted|transferred_out")
    public String deductionStatus;

    @Pattern(regexp = "not_applicable|submitted|approved|paid|archived|rejected")
    public String reimbursementStatus;

    @Null(message = "must be changed through the approval workflow")
    public String approvalStatus;

    @Pattern(regexp = "not_started|draft|posted|reversed")
    public String accountingStatus;

    public LocalDate issueDate;

    @Pattern(regexp = "pending_review|verified|linked|archived|rejected")
    public String status;

    @PositiveOrZero
    @Max(Integer.MAX_VALUE)
    public Long fileSize;

    @PositiveOrZero
    private Long transactionId;

    @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])")
    private String taxPeriod;

    @Size(max = 120)
    private String accountingVoucherNo;

    @Size(max = 4000)
    private String accountingEntry;

    @Size(max = 1000)
    private String businessPurpose;

    @Size(max = 160)
    private String expenseOwner;

    private LocalDate dueDate;

    @Size(max = 255)
    private String fileName;

    @Size(max = 128)
    private String fileType;

    @Size(max = 2000)
    private String note;

    private boolean transactionIdPresent;
    private boolean taxPeriodPresent;
    private boolean accountingVoucherNoPresent;
    private boolean accountingEntryPresent;
    private boolean businessPurposePresent;
    private boolean expenseOwnerPresent;
    private boolean dueDatePresent;
    private boolean fileNamePresent;
    private boolean fileTypePresent;
    private boolean notePresent;

    @JsonSetter("transactionId")
    public void setTransactionId(Long transactionId) {
        this.transactionIdPresent = true;
        this.transactionId = transactionId;
    }

    @JsonSetter("taxPeriod")
    public void setTaxPeriod(String taxPeriod) {
        this.taxPeriodPresent = true;
        this.taxPeriod = taxPeriod;
    }

    @JsonSetter("accountingVoucherNo")
    public void setAccountingVoucherNo(String accountingVoucherNo) {
        this.accountingVoucherNoPresent = true;
        this.accountingVoucherNo = accountingVoucherNo;
    }

    @JsonSetter("accountingEntry")
    public void setAccountingEntry(String accountingEntry) {
        this.accountingEntryPresent = true;
        this.accountingEntry = accountingEntry;
    }

    @JsonSetter("businessPurpose")
    public void setBusinessPurpose(String businessPurpose) {
        this.businessPurposePresent = true;
        this.businessPurpose = businessPurpose;
    }

    @JsonSetter("expenseOwner")
    public void setExpenseOwner(String expenseOwner) {
        this.expenseOwnerPresent = true;
        this.expenseOwner = expenseOwner;
    }

    @JsonSetter("dueDate")
    public void setDueDate(LocalDate dueDate) {
        this.dueDatePresent = true;
        this.dueDate = dueDate;
    }

    @JsonSetter("fileName")
    public void setFileName(String fileName) {
        this.fileNamePresent = true;
        this.fileName = fileName;
    }

    @JsonSetter("fileType")
    public void setFileType(String fileType) {
        this.fileTypePresent = true;
        this.fileType = fileType;
    }

    @JsonSetter("note")
    public void setNote(String note) {
        this.notePresent = true;
        this.note = note;
    }

    public Long transactionId() {
        return transactionId;
    }

    public String taxPeriod() {
        return taxPeriod;
    }

    public String accountingVoucherNo() {
        return accountingVoucherNo;
    }

    public String accountingEntry() {
        return accountingEntry;
    }

    public String businessPurpose() {
        return businessPurpose;
    }

    public String expenseOwner() {
        return expenseOwner;
    }

    public LocalDate dueDate() {
        return dueDate;
    }

    public String fileName() {
        return fileName;
    }

    public String fileType() {
        return fileType;
    }

    public String note() {
        return note;
    }

    @JsonIgnore
    public boolean hasTransactionId() {
        return transactionIdPresent;
    }

    @JsonIgnore
    public boolean hasTaxPeriod() {
        return taxPeriodPresent;
    }

    @JsonIgnore
    public boolean hasAccountingVoucherNo() {
        return accountingVoucherNoPresent;
    }

    @JsonIgnore
    public boolean hasAccountingEntry() {
        return accountingEntryPresent;
    }

    @JsonIgnore
    public boolean hasBusinessPurpose() {
        return businessPurposePresent;
    }

    @JsonIgnore
    public boolean hasExpenseOwner() {
        return expenseOwnerPresent;
    }

    @JsonIgnore
    public boolean hasDueDate() {
        return dueDatePresent;
    }

    @JsonIgnore
    public boolean hasFileName() {
        return fileNamePresent;
    }

    @JsonIgnore
    public boolean hasFileType() {
        return fileTypePresent;
    }

    @JsonIgnore
    public boolean hasNote() {
        return notePresent;
    }

    public ReceiptUpdateCommand toCommand() {
        return new ReceiptUpdateCommand(
            transactionId, transactionIdPresent, voucherNo, title, voucherType, direction, counterparty,
            amount, taxAmount, taxRate, taxPeriod, taxPeriodPresent, invoiceCheckStatus, deductionStatus,
            reimbursementStatus, accountingStatus, accountingVoucherNo, accountingVoucherNoPresent,
            accountingEntry, accountingEntryPresent, businessPurpose, businessPurposePresent,
            expenseOwner, expenseOwnerPresent, issueDate, dueDate, dueDatePresent, status,
            fileName, fileNamePresent, fileSize, fileType, fileTypePresent, note, notePresent
        );
    }
}
