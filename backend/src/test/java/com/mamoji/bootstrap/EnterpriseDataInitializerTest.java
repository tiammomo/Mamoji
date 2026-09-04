package com.mamoji.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EnterpriseDataInitializerTest {
    @Test
    void delegatesAllStartupConfigurationToTheAtomicCommand() {
        ProductionBootstrapCommand bootstrap = mock(ProductionBootstrapCommand.class);
        EnterpriseDataInitializer initializer = new EnterpriseDataInitializer(
            bootstrap,
            "owner@mamoji.test",
            "Strong-pass-123!",
            "Owner",
            12,
            true,
            "Company",
            "credit-code",
            "Industry",
            "Taxpayer",
            "CNY"
        );

        initializer.initialize();

        ArgumentCaptor<ProductionBootstrapCommand.Request> request = ArgumentCaptor.forClass(
            ProductionBootstrapCommand.Request.class
        );
        verify(bootstrap).execute(request.capture());
        assertEquals("owner@mamoji.test", request.getValue().adminEmail());
        assertEquals("Company", request.getValue().companyName());
        assertTrue(request.getValue().passwordRequireComplexity());
    }
}
