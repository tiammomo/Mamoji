package com.mamoji.tax.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Derives tax workflow projections and checks invariants before persistence. */
@Component
public class TaxItemPolicy {
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal LARGE_UNPAID_AMOUNT = new BigDecimal("50000");

    public TaxItem apply(
        TaxItem item,
        boolean statusProvided,
        boolean taxRateProvided,
        String defaultPolicyBasis,
        LocalDate today
    ) {
        if (!taxRateProvided) {
            item.taxRate = inferredTaxRate(item);
        }
        if ("paid".equals(item.status) && money(item.paidAmount).compareTo(money(item.taxAmount)) < 0) {
            item.paidAmount = money(item.taxAmount);
        }
        item.paymentStatus = paymentStatus(item);
        if (blank(item.filingStatus)) {
            item.filingStatus = filingStatus(item.status);
        }
        if (blank(item.frequency)) {
            item.frequency = frequency(item.period);
        }
        if (blank(item.responsiblePerson)) {
            item.responsiblePerson = "财务负责人";
        }
        if (blank(item.policyBasis)) {
            item.policyBasis = blank(defaultPolicyBasis) ? "CN-DEFAULT-DEMO-POLICY" : defaultPolicyBasis;
        }
        if (blank(item.sourceType)) {
            item.sourceType = "manual";
        }
        if ("paid".equals(item.paymentStatus)
            && (money(item.taxAmount).signum() > 0 || "paid".equals(item.status))) {
            item.filingStatus = "accepted";
            if (!statusProvided) {
                item.status = "paid";
            }
            if (blank(item.paymentDate)) {
                item.paymentDate = today.toString();
            }
        } else if (!statusProvided && LocalDate.parse(item.dueDate).isBefore(today)) {
            item.status = "overdue";
            item.filingStatus = "overdue";
        }
        item.riskLevel = riskLevel(item, today);
        validate(item);
        return item;
    }

    public BigDecimal inferredTaxRate(TaxItem item) {
        BigDecimal taxableAmount = money(item.taxableAmount);
        if (taxableAmount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return money(item.taxAmount)
            .multiply(ONE_HUNDRED)
            .divide(taxableAmount, 4, RoundingMode.HALF_UP);
    }

    public String filingStatus(String status) {
        return switch (status == null ? "" : status) {
            case "paid" -> "accepted";
            case "pending" -> "submitted";
            case "overdue" -> "overdue";
            case "estimated" -> "prepared";
            default -> "not_started";
        };
    }

    public String frequency(String period) {
        if (period != null && period.matches("\\d{4}-Q[1-4]")) {
            return "quarterly";
        }
        if (period != null && period.matches("\\d{4}")) {
            return "annual";
        }
        if (period != null && period.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return "one_time";
        }
        return "monthly";
    }

    private void validate(TaxItem item) {
        if (money(item.paidAmount).compareTo(money(item.taxAmount)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paidAmount must not exceed taxAmount");
        }
        if (!periodMatchesFrequency(item.period, item.frequency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "period does not match frequency");
        }
        if (money(item.taxRate).compareTo(ONE_HUNDRED) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taxRate must not exceed 100");
        }
    }

    private String paymentStatus(TaxItem item) {
        BigDecimal taxAmount = money(item.taxAmount);
        BigDecimal paidAmount = money(item.paidAmount);
        if (taxAmount.signum() <= 0 || paidAmount.compareTo(taxAmount) >= 0) {
            return "paid";
        }
        return paidAmount.signum() > 0 ? "partial" : "unpaid";
    }

    private String riskLevel(TaxItem item, LocalDate today) {
        BigDecimal unpaid = money(item.taxAmount).subtract(money(item.paidAmount));
        if (unpaid.signum() <= 0 || "paid".equals(item.status)) {
            return "low";
        }
        LocalDate dueDate = LocalDate.parse(item.dueDate);
        if (dueDate.isBefore(today) || "overdue".equals(item.status)) {
            return "high";
        }
        if (!dueDate.isAfter(today.plusDays(7)) || unpaid.compareTo(LARGE_UNPAID_AMOUNT) >= 0) {
            return "medium";
        }
        if ("manual".equals(item.sourceType) || "not_started".equals(item.filingStatus)) {
            return "medium";
        }
        return "low";
    }

    private boolean periodMatchesFrequency(String period, String frequency) {
        if (period == null || frequency == null) return false;
        return switch (frequency) {
            case "monthly" -> period.matches("\\d{4}-(0[1-9]|1[0-2])");
            case "quarterly" -> period.matches("\\d{4}-Q[1-4]");
            case "annual" -> period.matches("\\d{4}");
            case "one_time" -> validOneTimePeriod(period);
            default -> false;
        };
    }

    private boolean validOneTimePeriod(String period) {
        if (!period.matches("\\d{4}-(0[1-9]|1[0-2])-([0-2]\\d|3[01])")) return false;
        try {
            LocalDate.parse(period);
            return true;
        } catch (java.time.DateTimeException exception) {
            return false;
        }
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
