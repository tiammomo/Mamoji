package com.mamoji.recurring.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RecurringScheduleTest {
    @Test
    void monthlyScheduleClampsRequestedDayToMonthEnd() {
        RecurringItem item = item("monthly", "2026-01-31");
        item.dayOfMonth = 31;

        assertEquals(LocalDate.of(2026, 2, 28), RecurringSchedule.next(item));
    }

    @Test
    void weeklyScheduleUsesConfiguredWeekday() {
        RecurringItem item = item("weekly", "2026-09-07");
        item.dayOfWeek = 5;

        assertEquals(LocalDate.of(2026, 9, 18), RecurringSchedule.next(item));
    }

    @Test
    void yearlyScheduleClampsLeapDay() {
        RecurringItem item = item("yearly", "2024-02-29");
        item.monthOfYear = 2;
        item.dayOfMonth = 29;

        assertEquals(LocalDate.of(2025, 2, 28), RecurringSchedule.next(item));
    }

    private RecurringItem item(String frequency, String startDate) {
        RecurringItem item = new RecurringItem();
        item.frequency = frequency;
        item.interval = 1;
        item.startDate = startDate;
        return item;
    }
}
