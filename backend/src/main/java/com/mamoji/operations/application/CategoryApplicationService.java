package com.mamoji.operations.application;

import com.mamoji.common.PayloadReader;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.User;
import com.mamoji.operations.domain.Category;
import com.mamoji.service.support.AccessControlService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Operations application boundary for transaction category lifecycle. */
@Service
public class CategoryApplicationService {
    private final CategoryRepository repository;
    private final AccessControlService accessControl;

    public CategoryApplicationService(CategoryRepository repository, AccessControlService accessControl) {
        this.repository = repository;
        this.accessControl = accessControl;
    }

    @Transactional(readOnly = true)
    public List<Category> listCategories(String authorization, String type, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        return repository.findAll(user.id, company.id, type);
    }

    @Transactional
    public Category createCategory(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(
            user,
            PayloadReader.optionalLong(body.get("companyId")).orElse(null)
        );
        Category category = new Category();
        category.userId = user.id;
        category.companyId = company.id;
        category.name = PayloadReader.textOr(body.get("name"), "新分类");
        category.icon = PayloadReader.textOr(body.get("icon"), "💡");
        category.color = PayloadReader.textOr(body.get("color"), "#6366f1");
        category.type = PayloadReader.textOr(body.get("type"), "expense");
        category.status = 1;
        category.createdAt = OffsetDateTime.now().toString();
        category.updatedAt = category.createdAt;
        return repository.insert(category);
    }

    @Transactional
    public Category updateCategory(
        String authorization,
        long id,
        Long companyId,
        Map<String, Object> body
    ) {
        User user = accessControl.requireUser(authorization);
        Category existing = repository.findForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? existing.companyId : companyId);
        assertScopedOwner(existing, user.id, company.id);
        Category category = copyCategory(existing);
        if (body.containsKey("name")) category.name = PayloadReader.text(body.get("name"));
        if (body.containsKey("icon")) category.icon = PayloadReader.text(body.get("icon"));
        if (body.containsKey("color")) category.color = PayloadReader.text(body.get("color"));
        if (body.containsKey("type")) category.type = PayloadReader.text(body.get("type"));
        category.updatedAt = OffsetDateTime.now().toString();
        repository.update(category);
        return category;
    }

    @Transactional
    public void deleteCategory(String authorization, long id, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Category category = repository.findForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? category.companyId : companyId);
        assertScopedOwner(category, user.id, company.id);
        if (repository.hasAccountingReferences(category.id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Category is used by transactions or budgets");
        }
        repository.delete(category);
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
            throw new IllegalStateException("Failed to copy transaction category", ex);
        }
    }
}
