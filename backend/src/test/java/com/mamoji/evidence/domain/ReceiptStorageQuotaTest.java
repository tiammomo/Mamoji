package com.mamoji.evidence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReceiptStorageQuotaTest {
    @Test
    void reservesCapacityThroughTheExactMaximum() {
        ReceiptStorageQuota quota = new ReceiptStorageQuota(1_000L, 80);

        assertEquals(1_000L, quota.reserve(700L, 300L));
        assertTrue(quota.warningReached(800L));
        assertFalse(quota.warningReached(799L));
    }

    @Test
    void rejectsCapacityOverflowWithoutLongArithmeticOverflow() {
        ReceiptStorageQuota quota = new ReceiptStorageQuota(Long.MAX_VALUE, 99);

        ReceiptStorageQuota.CapacityExceededException exception = assertThrows(
            ReceiptStorageQuota.CapacityExceededException.class,
            () -> quota.reserve(Long.MAX_VALUE - 5L, 6L)
        );

        assertEquals(Long.MAX_VALUE - 5L, exception.usedBytes());
        assertEquals(6L, exception.requestedBytes());
        assertEquals(Long.MAX_VALUE, exception.maximumBytes());
        assertTrue(quota.warningReached(Long.MAX_VALUE));
    }

    @Test
    void rejectsInvalidPolicyAndUsageValues() {
        assertThrows(IllegalArgumentException.class, () -> new ReceiptStorageQuota(0L, 80));
        assertThrows(IllegalArgumentException.class, () -> new ReceiptStorageQuota(1_000L, 0));
        assertThrows(IllegalArgumentException.class, () -> new ReceiptStorageQuota(1_000L, 100));
        assertThrows(IllegalArgumentException.class, () -> new ReceiptStorageQuota(1_000L, 80).reserve(-1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new ReceiptStorageUsage(-1L, 0L));
    }
}
