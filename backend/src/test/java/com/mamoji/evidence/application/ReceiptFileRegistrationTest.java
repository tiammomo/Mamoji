package com.mamoji.evidence.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mamoji.evidence.domain.ReceiptFileDigest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class ReceiptFileRegistrationTest {
    private static final ReceiptFileDigest DIGEST = new ReceiptFileDigest("a".repeat(64));
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-05T08:30:00Z");

    @Test
    void acceptsCanonicalTypedMetadata() {
        assertDoesNotThrow(() -> new ReceiptFileRegistration(1, 2, DIGEST, "receipt.pdf", 10, CREATED_AT));
    }

    @Test
    void rejectsInvalidIdentifiersFileMetadataAndTimestamp() {
        assertThrows(IllegalArgumentException.class,
            () -> new ReceiptFileRegistration(0, 2, DIGEST, "receipt.pdf", 10, CREATED_AT));
        assertThrows(IllegalArgumentException.class,
            () -> new ReceiptFileRegistration(1, 2, DIGEST, " path/receipt.pdf", 10, CREATED_AT));
        assertThrows(IllegalArgumentException.class,
            () -> new ReceiptFileRegistration(1, 2, DIGEST, "receipt\n.pdf", 10, CREATED_AT));
        assertThrows(IllegalArgumentException.class,
            () -> new ReceiptFileRegistration(1, 2, DIGEST, "a".repeat(256), 10, CREATED_AT));
        assertThrows(IllegalArgumentException.class,
            () -> new ReceiptFileRegistration(1, 2, DIGEST, "receipt.pdf", -1, CREATED_AT));
        assertThrows(NullPointerException.class,
            () -> new ReceiptFileRegistration(1, 2, DIGEST, "receipt.pdf", 10, null));
    }
}
