package com.mamoji.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mamoji.platform.audit.application.AuditLogRepository;
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
            "currentUser",
            "deleteBudget",
            "findRegistrationInviteByToken",
            "registrationInvite",
            "registrationInviteForUpdate",
            "recalculateBudget",
            "refreshBudgetData",
            "refreshBudgetDataAfterCommit",
            "rememberToken",
            "revokeToken",
            "saveBudget",
            "saveRegistrationInvite",
            "sortedAccounts",
            "sortedBudgets",
            "sortedCategories",
            "sortedRegistrationInvites",
            "sortedTransactions"
        ));
        assertTrue(Arrays.stream(InMemoryStore.class.getDeclaredFields())
            .noneMatch(field -> field.getName().equals("registrationInvites")),
            "Registration invitations must not return to the process-local compatibility view");
    }

    @Test
    void enterpriseStoreDoesNotExposeUnusedMutationOrProjectionHelpers() {
        assertNotPublic(EnterpriseStore.class, Set.of(
            "attachDepartmentNames",
            "deleteEmployee",
            "saveEntityTransfer"
        ));
    }

    @Test
    void auditPersistenceBoundaryExposesNoMutationOrDeletionOperations() {
        Set<String> forbiddenPrefixes = Set.of("delete", "remove", "update", "replace", "save");
        Set<String> exposedMethods = Arrays.stream(AuditLogRepository.class.getDeclaredMethods())
            .map(method -> method.getName().toLowerCase())
            .filter(name -> forbiddenPrefixes.stream().anyMatch(name::startsWith))
            .collect(Collectors.toSet());

        assertTrue(exposedMethods.isEmpty(), () ->
            "Audit logs must remain append-only at the persistence boundary: " + exposedMethods
        );
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
