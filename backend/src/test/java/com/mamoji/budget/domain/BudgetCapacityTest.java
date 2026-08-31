package com.mamoji.budget.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mamoji.budget.domain.BudgetCapacity.BudgetCapacityExceededException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BudgetCapacityTest {
    @Test
    void reservesOnlyTheCurrentlyAvailableAmount() {
        BudgetCapacity capacity = new BudgetCapacity(
            new BigDecimal("1000"),
            new BigDecimal("600"),
            new BigDecimal("150")
        );

        BudgetCapacity reserved = capacity.reserve(new BigDecimal("250"));

        assertEquals(0, BigDecimal.ZERO.compareTo(reserved.available()));
        assertEquals(0, new BigDecimal("400").compareTo(reserved.reserved()));
    }

    @Test
    void rejectsAReservationThatWouldExceedCapacity() {
        BudgetCapacity capacity = new BudgetCapacity(
            new BigDecimal("1000"),
            new BigDecimal("600"),
            new BigDecimal("150")
        );

        BudgetCapacityExceededException exception = assertThrows(
            BudgetCapacityExceededException.class,
            () -> capacity.reserve(new BigDecimal("250.01"))
        );

        assertEquals(0, new BigDecimal("250").compareTo(exception.available()));
        assertEquals(0, new BigDecimal("250.01").compareTo(exception.requested()));
    }
}
