package com.mamoji.evidence.application;

/** Stable per-file failure returned from a batch receipt upload. */
public record ReceiptUploadFailure(String fileName, String reason, int status) {
}
