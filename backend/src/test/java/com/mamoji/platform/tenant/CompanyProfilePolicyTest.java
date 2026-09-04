package com.mamoji.platform.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CompanyProfilePolicyTest {
    @Test
    void initializationChoosesDefaultsFromNormalizedEntityType() {
        Company company = new Company();
        company.entityType = " HOUSEHOLD ";
        company.name = "Family Office";

        CompanyProfilePolicy.initialize(company);

        assertEquals("household", company.entityType);
        assertEquals("CN-HOUSEHOLD-ASSET-PROFILE", company.policyProfileKey);
    }
}
