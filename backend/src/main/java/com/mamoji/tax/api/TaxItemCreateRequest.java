package com.mamoji.tax.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record TaxItemCreateRequest(
    @Positive Long companyId,
    @Size(max = 160) @Pattern(regexp = "(?s).*\\S.*") String name,
    @Pattern(regexp = "(?i)\\s*\\d{4}(?:-(?:0[1-9]|1[0-2])|-Q[1-4]|-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01]))?\\s*") String period,
    @Pattern(regexp = "(?i)\\s*(vat|corporate_income_tax|personal_income_tax|surcharge|stamp_duty)\\s*") String taxType,
    @DecimalMin("0") @Digits(integer = 16, fraction = 4) BigDecimal taxableAmount,
    @DecimalMin("0") @Digits(integer = 16, fraction = 4) BigDecimal taxAmount,
    @DecimalMin("0") @Digits(integer = 16, fraction = 4) BigDecimal paidAmount,
    @DecimalMin("0") @Digits(integer = 16, fraction = 4) BigDecimal deductibleAmount,
    @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal taxRate,
    LocalDate dueDate,
    @Pattern(regexp = "(?i)\\s*(estimated|pending|paid|overdue)\\s*") String status,
    @Pattern(regexp = "(?i)\\s*(not_started|prepared|submitted|accepted|overdue)\\s*") String filingStatus,
    @Pattern(regexp = "(?i)\\s*(unpaid|partial|paid)\\s*") String paymentStatus,
    @Pattern(regexp = "(?i)\\s*(monthly|quarterly|annual|one_time)\\s*") String frequency,
    LocalDate declarationDate,
    LocalDate paymentDate,
    @Size(max = 120) @Pattern(regexp = "(?s).*\\S.*") String responsiblePerson,
    @Pattern(regexp = "(?i)\\s*(low|medium|high)\\s*") String riskLevel,
    @Size(max = 160) @Pattern(regexp = "(?s).*\\S.*") String policyBasis,
    @Pattern(regexp = "(?i)\\s*(manual|demo_estimate|transaction|receipt|payroll|policy)\\s*") String sourceType,
    @Size(max = 2000) String note
) {
}
