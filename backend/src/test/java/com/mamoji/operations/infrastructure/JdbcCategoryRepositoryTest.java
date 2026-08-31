package com.mamoji.operations.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mamoji.operations.domain.Category;
import com.mamoji.repository.InMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcCategoryRepositoryTest {
    @Test
    void rejectsAnUpdateWhenTheLockedCategoryDisappeared() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InMemoryStore compatibilityStore = mock(InMemoryStore.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcCategoryRepository repository = new JdbcCategoryRepository(jdbc, compatibilityStore);
        Category category = category(42);

        OptimisticLockingFailureException exception = assertThrows(
            OptimisticLockingFailureException.class,
            () -> repository.update(category)
        );

        assertEquals("Category was changed by another request: 42", exception.getMessage());
        verify(compatibilityStore, never()).synchronizeCategoryAfterCommit(category);
    }

    @Test
    void synchronizesCompatibilityViewAfterUpdate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InMemoryStore compatibilityStore = mock(InMemoryStore.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcCategoryRepository repository = new JdbcCategoryRepository(jdbc, compatibilityStore);
        Category category = category(42);

        repository.update(category);

        verify(compatibilityStore).synchronizeCategoryAfterCommit(category);
    }

    @Test
    void rejectsADeletionWhenTheLockedCategoryDisappeared() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InMemoryStore compatibilityStore = mock(InMemoryStore.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcCategoryRepository repository = new JdbcCategoryRepository(jdbc, compatibilityStore);

        assertThrows(OptimisticLockingFailureException.class, () -> repository.delete(category(42)));

        verify(compatibilityStore, never()).removeCategoryFromCompatibilityViewAfterCommit(42);
    }

    @Test
    void removesCompatibilityViewAfterDeletion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InMemoryStore compatibilityStore = mock(InMemoryStore.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcCategoryRepository repository = new JdbcCategoryRepository(jdbc, compatibilityStore);

        repository.delete(category(42));

        verify(compatibilityStore).removeCategoryFromCompatibilityViewAfterCommit(42);
    }

    private Category category(long id) {
        Category category = new Category();
        category.id = id;
        return category;
    }
}
