package com.mamoji.operations.application;

import com.mamoji.domain.Models.Category;
import java.util.List;
import java.util.Optional;

/** Persistence port for operations-owned transaction categories. */
public interface CategoryRepository {
    List<Category> findAll(long userId, long companyId, String type);

    Optional<Category> findById(long id);

    Optional<Category> findForUpdate(long id);

    Category insert(Category category);

    void update(Category category);

    boolean hasAccountingReferences(long categoryId);

    void delete(Category category);

    void ensureCompanyDefaults(long ownerId, long companyId);
}
