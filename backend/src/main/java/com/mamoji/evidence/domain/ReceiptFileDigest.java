package com.mamoji.evidence.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical lowercase SHA-256 identity for a receipt attachment. */
public record ReceiptFileDigest(String value) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public ReceiptFileDigest {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException("Receipt file digest must be a lowercase SHA-256 value");
        }
    }

    public static ReceiptFileDigest sha256(byte[] content) {
        Objects.requireNonNull(content, "content");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return new ReceiptFileDigest(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
