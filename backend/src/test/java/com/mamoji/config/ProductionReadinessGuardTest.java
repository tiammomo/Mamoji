package com.mamoji.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionReadinessGuardTest {

    @Test
    void rejectsProductionWithDemoDefaults() {
        ProductionReadinessGuard guard = new ProductionReadinessGuard(
            new MockEnvironment(),
            "production",
            "demo",
            "admin@example.com",
            "123456",
            "open",
            "http://localhost:33000",
            false,
            8,
            true,
            false,
            false,
            false,
            false,
            "mamoji",
            "minioadmin",
            "minioadmin",
            "http://localhost:9000"
        );

        assertThrows(IllegalStateException.class, guard::validate);
    }

    @Test
    void acceptsHardenedProductionSettings() {
        ProductionReadinessGuard guard = new ProductionReadinessGuard(
            new MockEnvironment(),
            "production",
            "bootstrap",
            "ops@company.test",
            "Admin-Password-123!",
            "invite",
            "https://mamoji.company.test",
            true,
            12,
            false,
            true,
            true,
            true,
            true,
            "postgres-password-123!",
            "minio-access-123",
            "minio-secret-password-123!",
            "https://mamoji.company.test"
        );

        assertDoesNotThrow(guard::validate);
    }
}
