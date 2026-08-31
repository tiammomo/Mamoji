package com.mamoji.operations.application;

import com.mamoji.operations.api.TransactionCreateRequest;
import com.mamoji.operations.domain.CreateTransactionCommand;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.service.AccountingService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Typed application boundary for transaction writes.
 *
 * <p>The persistence orchestration remains in {@link AccountingService} while the
 * rest of the operations module is migrated incrementally.</p>
 */
@Service
public class TransactionApplicationService {
    private final AccountingService accountingService;

    public TransactionApplicationService(AccountingService accountingService) {
        this.accountingService = accountingService;
    }

    public Map<String, Object> create(
        ActorContext actor,
        TransactionCreateRequest request,
        String headerIdempotencyKey
    ) {
        String idempotencyKey = headerIdempotencyKey == null || headerIdempotencyKey.isBlank()
            ? request.idempotencyKey()
            : headerIdempotencyKey;
        CreateTransactionCommand command = new CreateTransactionCommand(
            request.companyId(),
            request.type() == null ? 2 : request.type(),
            request.amount(),
            request.categoryId(),
            request.accountId(),
            request.date() == null ? LocalDate.now() : request.date(),
            request.note() == null ? "" : request.note(),
            idempotencyKey
        );
        return accountingService.createTransaction(actor.legacyAuthorization(), command);
    }
}
