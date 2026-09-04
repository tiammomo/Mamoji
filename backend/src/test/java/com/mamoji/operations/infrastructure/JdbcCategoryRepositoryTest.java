package com.mamoji.operations.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mamoji.operations.domain.Category;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcCategoryRepositoryTest {
    @Test
    void rejectsAnUpdateWhenTheLockedCategoryDisappeared() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcCategoryRepository repository = new JdbcCategoryRepository(jdbc);
        Category category = category(42);

        OptimisticLockingFailureException exception = assertThrows(
            OptimisticLockingFailureException.class,
            () -> repository.update(category)
        );

        assertEquals("Category was changed by another request: 42", exception.getMessage());
    }

    @Test
    void acceptsExactlyOneUpdatedDatabaseRow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcCategoryRepository repository = new JdbcCategoryRepository(jdbc);
        Category category = category(42);

        repository.update(category);
    }

    @Test
    void rejectsADeletionWhenTheLockedCategoryDisappeared() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcCategoryRepository repository = new JdbcCategoryRepository(jdbc);

        assertThrows(OptimisticLockingFailureException.class, () -> repository.delete(category(42)));
    }

    @Test
    void acceptsExactlyOneDeletedDatabaseRow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcCategoryRepository repository = new JdbcCategoryRepository(jdbc);

        repository.delete(category(42));
    }

    private Category category(long id) {
        Category category = new Category();
        category.id = id;
        category.name = "Category";
        category.icon = "C";
        category.color = "#112233";
        category.type = "expense";
        category.createdAt = "2026-09-02T00:00:00Z";
        category.updatedAt = category.createdAt;
        return category;
    }
}
