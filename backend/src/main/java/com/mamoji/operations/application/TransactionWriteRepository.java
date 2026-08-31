package com.mamoji.operations.application;

import com.mamoji.operations.domain.TransactionRecord;
import java.util.Optional;

/** Persistence port owned by transaction write use cases. */
public interface TransactionWriteRepository {
    void lockIdempotency(long companyId, String idempotencyKey);

    Optional<TransactionRecord> findByIdempotency(long companyId, String idempotencyKey);

    Optional<TransactionRecord> findForUpdate(long id);

    boolean hasRefunds(long transactionId);

    TransactionRecord insert(TransactionRecord transaction);

    void update(TransactionRecord transaction);

    void delete(TransactionRecord transaction);
}
