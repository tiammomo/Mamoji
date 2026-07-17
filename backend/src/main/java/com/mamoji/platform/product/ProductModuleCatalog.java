package com.mamoji.platform.product;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Defines the capabilities exposed when Mamoji is mounted as an internal
 * enterprise module. Organization and workforce cost are first-class company
 * capabilities; broader talent capabilities remain independently gated.
 */
@Component
public class ProductModuleCatalog {
    private static final Set<String> CORE_MODULES = Set.of(
        "workspace",
        "approvals",
        "operations",
        "transactions",
        "budgets",
        "reports",
        "recurring",
        "finance",
        "accounts",
        "evidence",
        "settings"
    );

    private final String mode;
    private final boolean householdEnabled;
    private final boolean peopleCoreEnabled;
    private final boolean workforceCostEnabled;
    private final boolean talentSuiteEnabled;
    private final boolean taxWorkspaceEnabled;
    private final boolean policyCenterEnabled;
    private final boolean backupUiEnabled;

    public ProductModuleCatalog(
        @Value("${mamoji.product.mode:internal-module}") String mode,
        @Value("${mamoji.product.modules.household-enabled:false}") boolean householdEnabled,
        @Value("${mamoji.product.modules.people-core-enabled:true}") boolean peopleCoreEnabled,
        @Value("${mamoji.product.modules.workforce-cost-enabled:true}") boolean workforceCostEnabled,
        @Value("${mamoji.product.modules.talent-suite-enabled:false}") boolean talentSuiteEnabled,
        @Value("${mamoji.product.modules.tax-workspace-enabled:false}") boolean taxWorkspaceEnabled,
        @Value("${mamoji.product.modules.policy-center-enabled:false}") boolean policyCenterEnabled,
        @Value("${mamoji.product.modules.backup-ui-enabled:false}") boolean backupUiEnabled
    ) {
        this.mode = mode == null || mode.isBlank() ? "internal-module" : mode.trim();
        this.householdEnabled = householdEnabled;
        this.peopleCoreEnabled = peopleCoreEnabled;
        this.workforceCostEnabled = workforceCostEnabled;
        this.talentSuiteEnabled = talentSuiteEnabled;
        this.taxWorkspaceEnabled = taxWorkspaceEnabled;
        this.policyCenterEnabled = policyCenterEnabled;
        this.backupUiEnabled = backupUiEnabled;
    }

    public ProductModules snapshot() {
        LinkedHashSet<String> enabled = new LinkedHashSet<>(CORE_MODULES);
        if (householdEnabled) {
            enabled.add("household");
        }
        if (peopleCoreEnabled) {
            enabled.add("people-core");
        }
        if (workforceCostEnabled) {
            enabled.add("workforce-cost");
        }
        if (talentSuiteEnabled) {
            enabled.add("talent-suite");
        }
        if (taxWorkspaceEnabled) {
            enabled.add("tax");
        }
        if (policyCenterEnabled) {
            enabled.add("policy");
        }
        if (backupUiEnabled) {
            enabled.add("backup");
        }
        return new ProductModules(mode, Collections.unmodifiableSet(enabled));
    }

    public boolean householdEnabled() {
        return householdEnabled;
    }

    public boolean isEnabled(String module) {
        return snapshot().isEnabled(module);
    }

    public record ProductModules(String mode, Set<String> enabled) {
        public boolean isEnabled(String module) {
            return enabled.contains(module);
        }
    }
}
