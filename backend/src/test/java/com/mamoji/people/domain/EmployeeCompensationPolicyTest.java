package com.mamoji.people.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EmployeeCompensationPolicyTest {
    @Test
    void hydrateCalculatesOneConsistentCompensationProjection() {
        Employee employee = employee("10000");
        EmployeeCompensationPolicy.initialize(employee, new BigDecimal("10000"));

        assertMoney("2350", employee.socialInsuranceCompanyAmount);
        assertMoney("1020", employee.socialInsurancePersonalAmount);
        assertMoney("1200", employee.housingFundCompanyAmount);
        assertMoney("1200", employee.housingFundPersonalAmount);
        assertMoney("13550", employee.monthlyCost);
        assertMoney("7780", employee.netPayEstimate);
        assertMoney("23.5", employee.socialInsuranceCompanyRate);
        assertMoney("10.2", employee.socialInsurancePersonalRate);
        assertEquals(5, employee.socialInsuranceItems.size());
        assertTrue(employee.socialInsuranceWarnings.isEmpty());
        assertFalse(EmployeeCompensationPolicy.hydrate(employee), "A second hydration must be idempotent");
    }

    @Test
    void hydrateAppliesOvertimeAndCompanyPolicyBounds() {
        Employee employee = employee("10000");
        EmployeeCompensationPolicy.initialize(employee, BigDecimal.ZERO);
        employee.weekdayOvertimeHours = new BigDecimal("8");
        employee.housingFundPersonalRate = new BigDecimal("1");
        employee.housingFundCompanyRate = new BigDecimal("99");
        employee.workInjuryCompanyRate = new BigDecimal("5");

        assertTrue(EmployeeCompensationPolicy.hydrate(employee));

        assertMoney("689.66", employee.overtimePay);
        assertMoney("5", employee.housingFundPersonalRate);
        assertMoney("12", employee.housingFundCompanyRate);
        assertMoney("1.4", employee.workInjuryCompanyRate);
        assertMoney("14359.66", employee.monthlyCost);
        assertMoney("9169.66", employee.netPayEstimate);
        assertEquals(3, employee.socialInsuranceWarnings.size());
    }

    private Employee employee(String salary) {
        Employee employee = new Employee();
        employee.salary = new BigDecimal(salary);
        employee.socialInsurance = BigDecimal.ZERO;
        employee.housingFund = BigDecimal.ZERO;
        employee.taxEstimate = BigDecimal.ZERO;
        return employee;
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "Expected " + expected + " but got " + actual);
    }
}
