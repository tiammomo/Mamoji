package com.mamoji.tax.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TaxItemPolicyTest {
    private final TaxItemPolicy policy = new TaxItemPolicy();
    private final LocalDate today = LocalDate.of(2026, 9, 4);

    @Test
    void zeroTaxDeclarationKeepsItsFilingWorkflowOpen() {
        TaxItem item = item("2026-Q3", "quarterly", "0", "0");
        item.status = "estimated";
        item.filingStatus = "prepared";
        item.sourceType = "demo_estimate";

        policy.apply(item, true, false, "CN-TEST", today);

        assertEquals("paid", item.paymentStatus);
        assertEquals("estimated", item.status);
        assertEquals("prepared", item.filingStatus);
        assertNull(item.paymentDate);
        assertEquals("low", item.riskLevel);
        assertEquals(0, BigDecimal.ZERO.compareTo(item.taxRate));
    }

    @Test
    void fullyPaidTaxSynchronizesTheDerivedLifecycle() {
        TaxItem item = item("2026-09", "monthly", "100", "100");

        policy.apply(item, false, false, "CN-TEST", today);

        assertEquals("paid", item.paymentStatus);
        assertEquals("paid", item.status);
        assertEquals("accepted", item.filingStatus);
        assertEquals(today.toString(), item.paymentDate);
        assertEquals(0, new BigDecimal("10.0000").compareTo(item.taxRate));
    }

    @Test
    void rejectsOverpaymentAndInvalidCalendarPeriods() {
        TaxItem overpaid = item("2026-09", "monthly", "100", "101");
        assertThrows(ResponseStatusException.class,
            () -> policy.apply(overpaid, false, false, "CN-TEST", today));

        TaxItem invalidDate = item("2026-02-31", "one_time", "100", "0");
        assertThrows(ResponseStatusException.class,
            () -> policy.apply(invalidDate, false, false, "CN-TEST", today));
    }

    private TaxItem item(String period, String frequency, String taxAmount, String paidAmount) {
        TaxItem item = new TaxItem();
        item.period = period;
        item.frequency = frequency;
        item.taxableAmount = new BigDecimal("1000");
        item.taxAmount = new BigDecimal(taxAmount);
        item.paidAmount = new BigDecimal(paidAmount);
        item.deductibleAmount = BigDecimal.ZERO;
        item.dueDate = "2026-10-15";
        item.status = "estimated";
        item.filingStatus = "prepared";
        item.sourceType = "manual";
        return item;
    }
}
