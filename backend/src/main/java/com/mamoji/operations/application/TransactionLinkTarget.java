package com.mamoji.operations.application;

/** Minimal immutable transaction identity exposed to cross-module link validation. */
public record TransactionLinkTarget(long transactionId, long companyId, long ownerUserId) {
    public TransactionLinkTarget {
        if (transactionId <= 0 || companyId <= 0 || ownerUserId <= 0) {
            throw new IllegalArgumentException("Transaction link target requires positive identifiers");
        }
    }
}
