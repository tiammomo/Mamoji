package com.mamoji.service.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EnterprisePermissionCatalogTest {
    private final EnterprisePermissionCatalog catalog = new EnterprisePermissionCatalog();

    @Test
    void financeRoleCannotReadPeopleButCanManageBudgets() {
        var permissions = catalog.permissionsForRole("finance_admin");

        assertTrue(permissions.contains("budget.manage"));
        assertTrue(permissions.contains("finance.write"));
        assertTrue(permissions.contains("workforce.cost.read"));
        assertTrue(permissions.contains("workforce.cost.manage"));
        assertFalse(permissions.contains("people.read"));
    }

    @Test
    void employeeOnlyGetsSelfServiceWorkflowCapabilities() {
        var permissions = catalog.permissionsForRole("employee");

        assertTrue(permissions.contains("people.read"));
        assertTrue(permissions.contains("approval.manage"));
        assertFalse(permissions.contains("finance.read"));
    }
}
