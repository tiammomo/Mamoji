package com.mamoji.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mamoji.platform.audit.application.AuditLogRepository;
import com.mamoji.platform.identity.account.application.LocalUserAccountRepository;
import com.mamoji.platform.identity.account.application.UserDirectory;
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
            "budget",
            "budgetForUpdate",
            "budgetHasTransactions",
            "currentUser",
            "deleteBudget",
            "findUser",
            "findUserByEmail",
            "findRegistrationInviteByToken",
            "findTransaction",
            "registrationInvite",
            "registrationInviteForUpdate",
            "recalculateBudget",
            "recurringForUpdate",
            "refreshBudgetData",
            "refreshBudgetDataAfterCommit",
            "rememberToken",
            "revokeToken",
            "saveBudget",
            "saveRecurring",
            "saveRegistrationInvite",
            "saveUser",
            "snapshot",
            "sortedAccounts",
            "sortedBudgets",
            "sortedCategories",
            "sortedRegistrationInvites",
            "sortedTransactions",
            "sortedUsers",
            "synchronizeAccountAfterCommit",
            "synchronizeCategoryAfterCommit",
            "synchronizeLedgerAfterCommit",
            "synchronizeLedgerMemberAfterCommit",
            "synchronizeTransactionAfterCommit",
            "synchronizeUserAccessAfterCommit",
            "deleteRecurring",
            "queryAllTransactions",
            "queryRecurring",
            "removeAccountFromCompatibilityViewAfterCommit",
            "removeCategoryFromCompatibilityViewAfterCommit",
            "removeLedgerMemberFromCompatibilityViewAfterCommit",
            "removeTransactionFromCompatibilityViewAfterCommit",
            "updatePasswordHashIfCurrent",
            "user",
            "userForUpdate"
        ));
        assertTrue(Arrays.stream(InMemoryStore.class.getDeclaredFields())
            .noneMatch(field -> field.getName().equals("registrationInvites")),
            "Registration invitations must not return to the process-local compatibility view");
        assertTrue(Arrays.stream(InMemoryStore.class.getDeclaredFields())
            .noneMatch(field -> field.getName().equals("users")),
            "Local user accounts must not return to the process-local compatibility view");
        assertTrue(Arrays.stream(InMemoryStore.class.getDeclaredFields())
            .noneMatch(field -> field.getName().equals("recurringItems")),
            "Recurring items must not return to the process-local compatibility view");
        assertTrue(Arrays.stream(InMemoryStore.class.getDeclaredFields())
            .noneMatch(field -> field.getName().equals("budgets")),
            "Budgets must not return to the process-local compatibility view");
        assertTrue(Arrays.stream(InMemoryStore.class.getDeclaredFields())
            .noneMatch(field -> field.getName().equals("transactions")),
            "Transactions must not return to the process-local compatibility view");
        assertTrue(Arrays.stream(InMemoryStore.class.getDeclaredFields())
            .noneMatch(field -> field.getName().equals("accounts")),
            "Accounts must not return to the process-local compatibility view");
        assertTrue(Arrays.stream(InMemoryStore.class.getDeclaredFields())
            .noneMatch(field -> field.getName().equals("ledgers")),
            "Ledgers must not return to the process-local compatibility view");
        assertTrue(Arrays.stream(InMemoryStore.class.getDeclaredFields())
            .noneMatch(field -> field.getName().equals("ledgerMembers")),
            "Ledger memberships must not return to the process-local compatibility view");
        assertTrue(Arrays.stream(InMemoryStore.class.getDeclaredFields())
            .noneMatch(field -> field.getName().equals("categories")),
            "Categories must not return to the process-local compatibility view");
    }

    @Test
    void enterpriseStoreDoesNotExposeUnusedMutationOrProjectionHelpers() {
        assertNotPublic(EnterpriseStore.class, Set.of(
            "attachDepartmentNames",
            "department",
            "deleteEmployee",
            "deleteTaxItem",
            "findActiveEmployeeByUser",
            "findDepartment",
            "findEmployee",
            "findTaxItem",
            "saveDepartment",
            "saveEmployee",
            "saveTaxItem",
            "saveEntityTransfer",
            "sortedDepartments",
            "sortedEmployees"
        ));
        assertTrue(Arrays.stream(EnterpriseStore.class.getDeclaredFields())
            .noneMatch(field -> field.getName().equals("departments")),
            "Departments must not return to the process-local enterprise compatibility view");
        assertTrue(Arrays.stream(EnterpriseStore.class.getDeclaredFields())
            .noneMatch(field -> field.getName().equals("employees")),
            "Employees must not return to the process-local enterprise compatibility view");
        assertTrue(Arrays.stream(EnterpriseStore.class.getDeclaredFields())
            .noneMatch(field -> field.getName().equals("taxItems")),
            "Tax items must not return to the process-local enterprise compatibility view");
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

    @Test
    void crossModuleUserDirectoryCannotExposeCredentials() {
        assertTrue(Arrays.stream(UserDirectory.Entry.class.getRecordComponents())
            .noneMatch(component -> component.getName().toLowerCase().contains("password")),
            "Cross-module user projections must remain password-free");
        assertTrue(Arrays.stream(LocalUserAccountRepository.class.getDeclaredMethods())
            .noneMatch(method -> method.getName().equals("findAll")),
            "Credential-bearing account repositories must not provide bulk cross-module reads");
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
