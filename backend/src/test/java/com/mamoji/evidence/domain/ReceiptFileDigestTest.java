package com.mamoji.evidence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReceiptFileDigestTest {
    @Test
    void createsCanonicalSha256Digest() {
        ReceiptFileDigest digest = ReceiptFileDigest.sha256("mamoji".getBytes(StandardCharsets.UTF_8));

        assertEquals("a86db6a738e69ff87f4393cf53e7eabd8935f20faab3e55e90055e811466862f", digest.value());
    }

    @Test
    void rejectsNonCanonicalDigestValues() {
        assertThrows(IllegalArgumentException.class, () -> new ReceiptFileDigest("a".repeat(63)));
        assertThrows(IllegalArgumentException.class, () -> new ReceiptFileDigest("A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new ReceiptFileDigest("not-a-digest"));
    }
}
