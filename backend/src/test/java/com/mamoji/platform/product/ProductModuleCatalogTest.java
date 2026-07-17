package com.mamoji.platform.product;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProductModuleCatalogTest {
    @Test
    void internalModuleModeIncludesPeopleAndWorkforceCostButKeepsTalentOptional() {
        ProductModuleCatalog catalog = new ProductModuleCatalog(
            "internal-module", false, true, true, false, false, false, false
        );

        var modules = catalog.snapshot();

        assertTrue(modules.isEnabled("workspace"));
        assertTrue(modules.isEnabled("budgets"));
        assertTrue(modules.isEnabled("finance"));
        assertTrue(modules.isEnabled("people-core"));
        assertTrue(modules.isEnabled("workforce-cost"));
        assertFalse(modules.isEnabled("household"));
        assertFalse(modules.isEnabled("talent-suite"));
        assertFalse(modules.isEnabled("organization"));
        assertFalse(modules.isEnabled("people"));
        assertFalse(modules.isEnabled("compensation"));
        assertFalse(modules.isEnabled("benefits"));
        assertFalse(modules.isEnabled("performance"));
        assertFalse(modules.isEnabled("tax"));
        assertFalse(modules.isEnabled("backup"));
    }
}
