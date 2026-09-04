package com.mamoji.operations.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TransactionLinkTargetTest {
    @Test
    void exposesOnlyCanonicalLinkOwnership() {
        TransactionLinkTarget target = new TransactionLinkTarget(42L, 7L, 9L);

        assertEquals(42L, target.transactionId());
        assertEquals(7L, target.companyId());
        assertEquals(9L, target.ownerUserId());
    }

    @Test
    void rejectsNonPositiveIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionLinkTarget(0L, 7L, 9L));
        assertThrows(IllegalArgumentException.class, () -> new TransactionLinkTarget(42L, 0L, 9L));
        assertThrows(IllegalArgumentException.class, () -> new TransactionLinkTarget(42L, 7L, 0L));
    }
}
