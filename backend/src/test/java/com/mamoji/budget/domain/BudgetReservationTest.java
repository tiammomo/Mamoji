package com.mamoji.budget.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class BudgetReservationTest {
    private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-08-31T10:00:00+08:00");
    private static final OffsetDateTime DECIDED = OffsetDateTime.parse("2026-08-31T10:05:00+08:00");

    @Test
    void confirmsAReservedAmountAgainstOneTransaction() {
        BudgetReservation confirmed = reserved().confirm(91, DECIDED);

        assertEquals(BudgetReservation.Status.CONFIRMED, confirmed.status());
        assertEquals(91L, confirmed.transactionId());
        assertEquals(DECIDED, confirmed.confirmedAt());
        assertNull(confirmed.releasedAt());
    }

    @Test
    void releasesAReservedAmountWithAnOperationalReason() {
        BudgetReservation released = reserved().release(" approval rejected ", DECIDED);

        assertEquals(BudgetReservation.Status.RELEASED, released.status());
        assertEquals("approval rejected", released.releaseReason());
        assertEquals(DECIDED, released.releasedAt());
        assertNull(released.transactionId());
    }

    @Test
    void terminalReservationsCannotTransitionAgain() {
        BudgetReservation confirmed = reserved().confirm(91, DECIDED);
        BudgetReservation released = reserved().release("cancelled", DECIDED);

        BudgetReservation reversed = confirmed.release("transaction deleted", DECIDED.plusMinutes(1));
        assertEquals(BudgetReservation.Status.RELEASED, reversed.status());
        assertThrows(IllegalStateException.class, () -> released.confirm(92, DECIDED.plusMinutes(1)));
        assertThrows(IllegalStateException.class, () -> released.release("retry", DECIDED.plusMinutes(1)));
    }

    private BudgetReservation reserved() {
        return new BudgetReservation(
            7,
            0,
            3,
            5,
            null,
            "expense:123",
            new BigDecimal("88.50"),
            BudgetReservation.Status.RESERVED,
            11,
            null,
            null,
            null,
            CREATED,
            CREATED
        );
    }
}
