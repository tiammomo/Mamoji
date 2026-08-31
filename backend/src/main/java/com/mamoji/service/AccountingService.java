package com.mamoji.service;

import com.mamoji.common.PayloadReader;
import com.mamoji.domain.Models.Category;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Transitional category application service pending the operations category boundary. */
@Service
public class AccountingService {
    private final InMemoryStore store;
    private final AccessControlService accessControl;

    public AccountingService(InMemoryStore store, AccessControlService accessControl) {
        this.store = store;
        this.accessControl = accessControl;
    }

    public List<Category> listCategories(String authorization, String type, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        return store.queryCategories(user.id, company.id, type);
    }

    @Transactional
    public Category createCategory(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(
            user,
            PayloadReader.optionalLong(body.get("companyId")).orElse(null)
        );
        return store.category(
            user.id,
            company.id,
            PayloadReader.textOr(body.get("name"), "新分类"),
            PayloadReader.textOr(body.get("icon"), "💡"),
            PayloadReader.textOr(body.get("color"), "#6366f1"),
            PayloadReader.textOr(body.get("type"), "expense")
        );
    }

    @Transactional
    public Category updateCategory(
        String authorization,
        long id,
        Long companyId,
        Map<String, Object> body
    ) {
        User user = accessControl.requireUser(authorization);
        Category existing = store.categoryForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? existing.companyId : companyId);
        assertScopedOwner(existing, user.id, company.id);
        Category category = copyCategory(existing);
        if (body.containsKey("name")) category.name = PayloadReader.text(body.get("name"));
        if (body.containsKey("icon")) category.icon = PayloadReader.text(body.get("icon"));
        if (body.containsKey("color")) category.color = PayloadReader.text(body.get("color"));
        if (body.containsKey("type")) category.type = PayloadReader.text(body.get("type"));
        category.updatedAt = OffsetDateTime.now().toString();
        store.saveCategory(category);
        return category;
    }

    @Transactional
    public void deleteCategory(String authorization, long id, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Category category = store.categoryForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? category.companyId : companyId);
        assertScopedOwner(category, user.id, company.id);
        if (store.categoryHasAccountingReferences(category.id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Category is used by transactions or budgets");
        }
        store.deleteCategory(id);
    }

    private void assertScopedOwner(Category category, long userId, long companyId) {
        if (category.userId != userId || !Objects.equals(category.companyId, companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private Category copyCategory(Category source) {
        Category target = new Category();
        try {
            for (var field : source.getClass().getFields()) field.set(target, field.get(source));
            return target;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to copy category", ex);
        }
    }
}
