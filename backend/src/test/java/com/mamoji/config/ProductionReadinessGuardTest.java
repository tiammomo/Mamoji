package com.mamoji.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionReadinessGuardTest {

    @Test
    void rejectsProductionWithDemoDefaults() {
        ProductionReadinessGuard guard = new ProductionReadinessGuard(
            new MockEnvironment(),
            "production",
            false,
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
        assertDoesNotThrow(() -> hardenedProductionGuard("https://mamoji.company.test").validate());
        assertDoesNotThrow(() -> hardenedProductionGuard("https://mamoji.company.test/").validate());
    }

    @Test
    void rejectsMinioExternalUrlThatIsNotAnOrigin() {
        List<String> invalidUrls = List.of(
            "https://mamoji.company.test/minio",
            "https://mamoji.company.test?download=true",
            "https://mamoji.company.test#receipts"
        );

        for (String invalidUrl : invalidUrls) {
            IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> hardenedProductionGuard(invalidUrl).validate(),
                invalidUrl
            );
            assertTrue(
                exception.getMessage().contains(
                    "MAMOJI_MINIO_EXTERNAL_URL must be a production https:// origin without a path, query, or fragment"
                ),
                invalidUrl
            );
        }
    }

    private ProductionReadinessGuard hardenedProductionGuard(String minioExternalUrl) {
        return new ProductionReadinessGuard(
            new MockEnvironment(),
            "production",
            true,
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
            minioExternalUrl
        );
    }
}
