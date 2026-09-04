package com.mamoji.evidence.application;

/** Binary attachment returned by the receipt download use case. */
public record ReceiptFileDownload(byte[] content, String fileName, String contentType) {
}
