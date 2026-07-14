package com.mamoji.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProductionReadinessGuard {
    private final boolean production;
    private final boolean singleInstanceGuardEnabled;
    private final String bootstrapMode;
    private final String bootstrapAdminEmail;
    private final String bootstrapAdminPassword;
    private final String registrationMode;
    private final String allowedOrigins;
    private final boolean passwordRequireComplexity;
    private final int passwordMinLength;
    private final boolean schemaCompatibilityEnabled;
    private final boolean flywayEnabled;
    private final boolean outboxEnabled;
    private final boolean outboxConsumerEnabled;
    private final boolean objectStorageEnabled;
    private final String datasourcePassword;
    private final String minioAccessKey;
    private final String minioSecretKey;
    private final String minioExternalUrl;

    public ProductionReadinessGuard(
        Environment environment,
        @Value("${mamoji.runtime.environment:local}") String runtimeEnvironment,
        @Value("${mamoji.runtime.single-instance-guard-enabled:true}") boolean singleInstanceGuardEnabled,
        @Value("${mamoji.bootstrap.mode:demo}") String bootstrapMode,
        @Value("${mamoji.bootstrap.admin-email:test@mamoji.com}") String bootstrapAdminEmail,
        @Value("${mamoji.bootstrap.admin-password:123456}") String bootstrapAdminPassword,
        @Value("${mamoji.registration.mode:open}") String registrationMode,
        @Value("${mamoji.security.cors.allowed-origins:}") String allowedOrigins,
        @Value("${mamoji.security.password.require-complexity:false}") boolean passwordRequireComplexity,
        @Value("${mamoji.security.password.min-length:12}") int passwordMinLength,
        @Value("${mamoji.schema.compatibility-enabled:true}") boolean schemaCompatibilityEnabled,
        @Value("${spring.flyway.enabled:true}") boolean flywayEnabled,
        @Value("${mamoji.outbox.enabled:true}") boolean outboxEnabled,
        @Value("${mamoji.outbox.consumer.enabled:true}") boolean outboxConsumerEnabled,
        @Value("${mamoji.object-storage.enabled:false}") boolean objectStorageEnabled,
        @Value("${spring.datasource.password:}") String datasourcePassword,
        @Value("${mamoji.object-storage.access-key:minioadmin}") String minioAccessKey,
        @Value("${mamoji.object-storage.secret-key:minioadmin}") String minioSecretKey,
        @Value("${mamoji.object-storage.external-url:}") String minioExternalUrl
    ) {
        this.production = isProduction(runtimeEnvironment, environment);
        this.singleInstanceGuardEnabled = singleInstanceGuardEnabled;
        this.bootstrapMode = value(bootstrapMode);
        this.bootstrapAdminEmail = value(bootstrapAdminEmail);
        this.bootstrapAdminPassword = value(bootstrapAdminPassword);
        this.registrationMode = value(registrationMode);
        this.allowedOrigins = value(allowedOrigins);
        this.passwordRequireComplexity = passwordRequireComplexity;
        this.passwordMinLength = passwordMinLength;
        this.schemaCompatibilityEnabled = schemaCompatibilityEnabled;
        this.flywayEnabled = flywayEnabled;
        this.outboxEnabled = outboxEnabled;
        this.outboxConsumerEnabled = outboxConsumerEnabled;
        this.objectStorageEnabled = objectStorageEnabled;
        this.datasourcePassword = value(datasourcePassword);
        this.minioAccessKey = value(minioAccessKey);
        this.minioSecretKey = value(minioSecretKey);
        this.minioExternalUrl = value(minioExternalUrl);
    }

    @PostConstruct
    public void validate() {
        if (!production) {
            return;
        }
        List<String> problems = new ArrayList<>();
        if (!singleInstanceGuardEnabled) {
            problems.add("MAMOJI_SINGLE_INSTANCE_GUARD_ENABLED must be true in production");
        }
        requireEquals(problems, "MAMOJI_BOOTSTRAP_MODE", bootstrapMode, "bootstrap");
        requireEmail(problems, "MAMOJI_BOOTSTRAP_ADMIN_EMAIL", bootstrapAdminEmail);
        requireStrongSecret(problems, "MAMOJI_BOOTSTRAP_ADMIN_PASSWORD", bootstrapAdminPassword, 12);
        requireEquals(problems, "MAMOJI_REGISTRATION_MODE", registrationMode, "invite");
        requireProductionOrigins(problems, allowedOrigins);
        if (!passwordRequireComplexity) {
            problems.add("MAMOJI_PASSWORD_REQUIRE_COMPLEXITY must be true");
        }
        if (passwordMinLength < 12) {
            problems.add("MAMOJI_PASSWORD_MIN_LENGTH must be at least 12");
        }
        if (schemaCompatibilityEnabled) {
            problems.add("MAMOJI_SCHEMA_COMPATIBILITY_ENABLED must be false in production");
        }
        if (!flywayEnabled) {
            problems.add("MAMOJI_FLYWAY_ENABLED must be true");
        }
        if (!outboxEnabled || !outboxConsumerEnabled) {
            problems.add("MAMOJI_OUTBOX_ENABLED and MAMOJI_OUTBOX_CONSUMER_ENABLED must both be true");
        }
        if (!objectStorageEnabled) {
            problems.add("MAMOJI_OBJECT_STORAGE_ENABLED must be true");
        }
        requireStrongSecret(problems, "MAMOJI_POSTGRES_PASSWORD", datasourcePassword, 16);
        requireStrongSecret(problems, "MAMOJI_MINIO_ACCESS_KEY", minioAccessKey, 12);
        requireStrongSecret(problems, "MAMOJI_MINIO_SECRET_KEY", minioSecretKey, 16);
        requireHttpsOrigin(problems, "MAMOJI_MINIO_EXTERNAL_URL", minioExternalUrl);

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Production readiness check failed: " + String.join("; ", problems));
        }
    }

    private boolean isProduction(String runtimeEnvironment, Environment environment) {
        if ("production".equalsIgnoreCase(value(runtimeEnvironment)) || "prod".equalsIgnoreCase(value(runtimeEnvironment))) {
            return true;
        }
        return Arrays.stream(environment.getActiveProfiles()).anyMatch(profile -> "prod".equalsIgnoreCase(profile));
    }

    private void requireEquals(List<String> problems, String name, String actual, String expected) {
        if (!expected.equalsIgnoreCase(actual)) {
            problems.add(name + " must be " + expected);
        }
    }

    private void requireEmail(List<String> problems, String name, String value) {
        if (value.isBlank() || !value.contains("@") || unsafePlaceholder(value)) {
            problems.add(name + " must be a real email address");
        }
    }

    private void requireStrongSecret(List<String> problems, String name, String value, int minLength) {
        if (value.length() < minLength || unsafePlaceholder(value)) {
            problems.add(name + " must be replaced with a strong secret of at least " + minLength + " characters");
        }
    }

    private void requireHttpsOrigin(List<String> problems, String name, String value) {
        if (unsafePlaceholder(value) || !isHttpsOrigin(value)) {
            problems.add(name + " must be a production https:// origin without a path, query, or fragment");
        }
    }

    private boolean isHttpsOrigin(String value) {
        try {
            URI uri = new URI(value);
            String path = uri.getRawPath();
            return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && (path == null || path.isEmpty() || "/".equals(path))
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
        } catch (URISyntaxException | IllegalArgumentException ex) {
            return false;
        }
    }

    private void requireProductionOrigins(List<String> problems, String origins) {
        if (origins.isBlank()) {
            problems.add("MAMOJI_ALLOWED_ORIGINS must not be empty");
            return;
        }
        for (String origin : origins.split(",")) {
            String normalized = value(origin).toLowerCase(Locale.ROOT);
            if (normalized.isBlank() || "*".equals(normalized) || normalized.contains("localhost")
                || normalized.contains("127.0.0.1") || normalized.contains("example.com")
                || !normalized.startsWith("https://")) {
                problems.add("MAMOJI_ALLOWED_ORIGINS contains a non-production origin: " + origin.trim());
            }
        }
    }

    private boolean unsafePlaceholder(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.isBlank()
            || normalized.contains("replace-with")
            || normalized.contains("example.com")
            || "123456".equals(normalized)
            || "mamoji".equals(normalized)
            || "minioadmin".equals(normalized)
            || "admin@example.com".equals(normalized);
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }
}
