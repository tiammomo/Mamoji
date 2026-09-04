package com.mamoji.operations.application;

import java.util.Optional;

/** Narrow read contract for modules that link their records to an operations transaction. */
public interface TransactionLinkQuery {
    Optional<TransactionLinkTarget> findById(long transactionId);
}
