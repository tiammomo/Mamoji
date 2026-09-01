package com.mamoji.budget.application;

import com.mamoji.budget.domain.Budget;
import com.mamoji.operations.domain.TransactionRecord;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for budget definitions and their stored projections. */
public interface BudgetRepository {
    List<Budget> findByCompany(long companyId);

    Optional<Budget> findById(long companyId, long id);

    Optional<Budget> findByIdForUpdate(long id);

    Budget insert(Budget budget);

    void update(Budget budget);

    void delete(long id);

    boolean hasTransactions(long id);

    Optional<CategoryRef> category(long id);

    Optional<Long> matchingBudgetId(TransactionRecord transaction);

    void persistProjection(Budget budget);

    record CategoryRef(long id, Long companyId, String type) {
    }
}
