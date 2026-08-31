package com.mamoji.operations.application;

import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import com.mamoji.domain.Models.Company;
import com.mamoji.operations.api.TransactionQueryRequest;
import com.mamoji.operations.domain.TransactionRecord;
import com.mamoji.operations.domain.TransactionSearchCriteria;
import com.mamoji.operations.domain.TransactionSummary;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.identity.User;
import com.mamoji.service.support.AccessControlService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Read-only application boundary for transaction list, summary, and detail queries. */
@Service
public class TransactionQueryService {
    private final TransactionQueryRepository repository;
    private final AccessControlService accessControl;

    public TransactionQueryService(TransactionQueryRepository repository, AccessControlService accessControl) {
        this.repository = repository;
        this.accessControl = accessControl;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PagedResponse<TransactionRecord> list(ActorContext actor, TransactionQueryRequest request) {
        QueryContext context = context(actor, request);
        return repository.findPage(
            context.user().id,
            context.company().id,
            context.criteria(),
            new PageRequest(request.resolvedPage(), request.resolvedSize())
        );
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TransactionSummary summary(ActorContext actor, TransactionQueryRequest request) {
        QueryContext context = context(actor, request);
        return repository.summarize(
            context.user().id,
            context.company().id,
            context.criteria()
        );
    }

    @Transactional(readOnly = true)
    public TransactionRecord get(ActorContext actor, long id, Long companyId) {
        User user = accessControl.requireUser(actor.legacyAuthorization());
        TransactionRecord transaction = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
        Company company = accessControl.resolveCompany(
            user,
            companyId == null ? transaction.companyId : companyId
        );
        if (transaction.userId != user.id || transaction.companyId == null || transaction.companyId != company.id) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        return transaction;
    }

    private QueryContext context(ActorContext actor, TransactionQueryRequest request) {
        User user = accessControl.requireUser(actor.legacyAuthorization());
        Company company = accessControl.resolveCompany(user, request.companyId());
        validateBoundaries(request);
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(
            request.type(),
            request.categoryId(),
            request.accountId(),
            request.startDate(),
            request.endDate(),
            request.keyword(),
            request.minAmount(),
            request.maxAmount()
        );
        return new QueryContext(user, company, criteria);
    }

    private void validateBoundaries(TransactionQueryRequest request) {
        if (request.startDate() != null
            && request.endDate() != null
            && request.startDate().isAfter(request.endDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate must not be after endDate");
        }
        if (request.minAmount() != null
            && request.maxAmount() != null
            && request.minAmount().compareTo(request.maxAmount()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minAmount must not exceed maxAmount");
        }
    }

    private record QueryContext(User user, Company company, TransactionSearchCriteria criteria) {
    }
}
