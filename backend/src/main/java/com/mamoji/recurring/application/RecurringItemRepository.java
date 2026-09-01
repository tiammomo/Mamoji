package com.mamoji.recurring.application;

import com.mamoji.recurring.domain.RecurringItem;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for recurring accounting rules. */
public interface RecurringItemRepository {
    List<RecurringItem> findByOwnerAndCompany(long userId, long companyId);

    Optional<RecurringItem> findByIdForUpdate(String id);

    RecurringItem insert(RecurringItem item);

    void update(RecurringItem item);

    void delete(String id);
}
