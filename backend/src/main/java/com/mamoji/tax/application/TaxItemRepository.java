package com.mamoji.tax.application;

import com.mamoji.tax.domain.TaxItem;
import java.util.List;
import java.util.Optional;

/** Persistence port for authoritative company-scoped tax work items. */
public interface TaxItemRepository {
    List<TaxItem> findByCompany(long companyId);

    List<TaxItem> findAll();

    Optional<TaxItem> findByIdForUpdate(long id);

    TaxItem insert(TaxItem item);

    void update(TaxItem item);

    void delete(long id);

    boolean hasLifecycleHistory(long companyId);
}
