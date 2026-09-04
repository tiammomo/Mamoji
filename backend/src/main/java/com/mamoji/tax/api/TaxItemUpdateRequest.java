package com.mamoji.tax.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Partial PUT payload; explicit null clears nullable fields or resets required defaults. */
public class TaxItemUpdateRequest {
    @Size(max = 160)
    @Pattern(regexp = "(?s).*\\S.*")
    public String name;

    @Pattern(regexp = "(?i)\\s*\\d{4}(?:-(?:0[1-9]|1[0-2])|-Q[1-4]|-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01]))?\\s*")
    public String period;

    @Pattern(regexp = "(?i)\\s*(vat|corporate_income_tax|personal_income_tax|surcharge|stamp_duty)\\s*")
    public String taxType;

    @DecimalMin("0")
    @Digits(integer = 16, fraction = 4)
    public BigDecimal taxableAmount;

    @DecimalMin("0")
    @Digits(integer = 16, fraction = 4)
    public BigDecimal taxAmount;

    @DecimalMin("0")
    @Digits(integer = 16, fraction = 4)
    public BigDecimal paidAmount;

    @DecimalMin("0")
    @Digits(integer = 16, fraction = 4)
    public BigDecimal deductibleAmount;

    @DecimalMin("0")
    @DecimalMax("100")
    @Digits(integer = 3, fraction = 4)
    public BigDecimal taxRate;

    public LocalDate dueDate;

    @Pattern(regexp = "(?i)\\s*(estimated|pending|paid|overdue)\\s*")
    public String status;

    @Pattern(regexp = "(?i)\\s*(not_started|prepared|submitted|accepted|overdue)\\s*")
    public String filingStatus;

    @Pattern(regexp = "(?i)\\s*(unpaid|partial|paid)\\s*")
    public String paymentStatus;

    @Pattern(regexp = "(?i)\\s*(monthly|quarterly|annual|one_time)\\s*")
    public String frequency;

    @Pattern(regexp = "(?i)\\s*(low|medium|high)\\s*")
    public String riskLevel;

    @Pattern(regexp = "(?i)\\s*(manual|demo_estimate|transaction|receipt|payroll|policy)\\s*")
    public String sourceType;

    private LocalDate declarationDate;
    private LocalDate paymentDate;

    @Size(max = 120)
    @Pattern(regexp = "(?s).*\\S.*")
    private String responsiblePerson;

    @Size(max = 160)
    @Pattern(regexp = "(?s).*\\S.*")
    private String policyBasis;

    @Size(max = 2000)
    private String note;
    private boolean declarationDatePresent;
    private boolean paymentDatePresent;
    private boolean responsiblePersonPresent;
    private boolean policyBasisPresent;
    private boolean notePresent;

    @JsonSetter("declarationDate")
    public void setDeclarationDate(LocalDate declarationDate) {
        this.declarationDatePresent = true;
        this.declarationDate = declarationDate;
    }

    @JsonSetter("paymentDate")
    public void setPaymentDate(LocalDate paymentDate) {
        this.paymentDatePresent = true;
        this.paymentDate = paymentDate;
    }

    @JsonSetter("responsiblePerson")
    public void setResponsiblePerson(String responsiblePerson) {
        this.responsiblePersonPresent = true;
        this.responsiblePerson = responsiblePerson;
    }

    @JsonSetter("policyBasis")
    public void setPolicyBasis(String policyBasis) {
        this.policyBasisPresent = true;
        this.policyBasis = policyBasis;
    }

    @JsonSetter("note")
    public void setNote(String note) {
        this.notePresent = true;
        this.note = note;
    }

    public LocalDate declarationDate() {
        return declarationDate;
    }

    public LocalDate paymentDate() {
        return paymentDate;
    }

    public String responsiblePerson() {
        return responsiblePerson;
    }

    public String policyBasis() {
        return policyBasis;
    }

    public String note() {
        return note;
    }

    @JsonIgnore
    public boolean hasDeclarationDate() {
        return declarationDatePresent;
    }

    @JsonIgnore
    public boolean hasPaymentDate() {
        return paymentDatePresent;
    }

    @JsonIgnore
    public boolean hasResponsiblePerson() {
        return responsiblePersonPresent;
    }

    @JsonIgnore
    public boolean hasPolicyBasis() {
        return policyBasisPresent;
    }

    @JsonIgnore
    public boolean hasNote() {
        return notePresent;
    }
}
