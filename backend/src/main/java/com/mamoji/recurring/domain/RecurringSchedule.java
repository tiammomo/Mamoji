package com.mamoji.recurring.domain;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

/** Calendar-aware cursor calculation for recurring rules. */
public final class RecurringSchedule {
    private RecurringSchedule() {
    }

    public static LocalDate next(RecurringItem item) {
        LocalDate base = LocalDate.parse(item.lastExecuted == null ? item.startDate : item.lastExecuted);
        int interval = Math.max(1, item.interval);
        try {
            return switch (item.frequency) {
                case "daily" -> base.plusDays(interval);
                case "weekly" -> weekly(base, interval, item.dayOfWeek);
                case "yearly" -> yearly(base, interval, item.monthOfYear, item.dayOfMonth);
                default -> monthly(base, interval, item.dayOfMonth);
            };
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException("Recurring schedule exceeds the supported calendar range", exception);
        }
    }

    private static LocalDate weekly(LocalDate base, int interval, Integer requestedDay) {
        LocalDate targetWeek = base.plusWeeks(interval);
        DayOfWeek day = requestedDay == null ? base.getDayOfWeek() : DayOfWeek.of(requestedDay);
        return targetWeek.plusDays(day.getValue() - targetWeek.getDayOfWeek().getValue());
    }

    private static LocalDate monthly(LocalDate base, int interval, Integer requestedDay) {
        YearMonth month = YearMonth.from(base).plusMonths(interval);
        int day = requestedDay == null ? base.getDayOfMonth() : requestedDay;
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }

    private static LocalDate yearly(LocalDate base, int interval, Integer requestedMonth, Integer requestedDay) {
        int year = Math.addExact(base.getYear(), interval);
        int month = requestedMonth == null ? base.getMonthValue() : requestedMonth;
        YearMonth target = YearMonth.of(year, month);
        int day = requestedDay == null ? base.getDayOfMonth() : requestedDay;
        return target.atDay(Math.min(day, target.lengthOfMonth()));
    }
}
