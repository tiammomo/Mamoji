package com.mamoji.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class LegacyStoreBoundaryTest {
    @Test
    void inMemoryStoreDoesNotExposeModuleOwnedOrInternalCompatibilityOperations() {
        assertNotPublic(InMemoryStore.class, Set.of(
            "attachBudgetData",
            "attachCategory",
            "attachTransactionRelations",
            "budgetForUpdate",
            "budgetHasTransactions",
            "deleteBudget",
            "findRegistrationInviteByToken",
            "recalculateBudget",
            "refreshBudgetData",
            "refreshBudgetDataAfterCommit",
            "saveBudget",
            "sortedAccounts",
            "sortedBudgets",
            "sortedCategories",
            "sortedTransactions"
        ));
    }

    @Test
    void enterpriseStoreDoesNotExposeUnusedMutationOrProjectionHelpers() {
        assertNotPublic(EnterpriseStore.class, Set.of(
            "attachDepartmentNames",
            "deleteEmployee",
            "saveEntityTransfer"
        ));
    }

    private static void assertNotPublic(Class<?> storeType, Set<String> forbiddenMethods) {
        Set<String> exposedMethods = Arrays.stream(storeType.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .map(method -> method.getName())
            .filter(forbiddenMethods::contains)
            .collect(Collectors.toSet());

        assertTrue(exposedMethods.isEmpty(), () -> storeType.getSimpleName()
            + " must not expose legacy compatibility operations: " + exposedMethods);
    }
}
