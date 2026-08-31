package com.mamoji.operations.application;

import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.operations.domain.TransactionSearchCriteria;
import com.mamoji.operations.domain.TransactionSummary;
import java.util.Optional;

/** Persistence port owned by the transaction query use cases. */
public interface TransactionQueryRepository {
    PagedResponse<TransactionRecord> findPage(
        long userId,
        long companyId,
        TransactionSearchCriteria criteria,
        PageRequest pageRequest
    );

    TransactionSummary summarize(long userId, long companyId, TransactionSearchCriteria criteria);

    Optional<TransactionRecord> findById(long id);
}
