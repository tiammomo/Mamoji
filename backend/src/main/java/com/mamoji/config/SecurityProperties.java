package com.mamoji.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Externalized security configuration bound from {@code app.security.*}.
 *
 * <p>This bean centralizes toggles for development diagnostics, frame headers, and CORS policy.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private boolean h2ConsoleEnabled = false;
    private boolean prometheusPublicEnabled = false;

    /**
     * Supported values: deny, sameorigin, disable.
     */
    private String frameOptions = "deny";

    private final Cors cors = new Cors();

    /**
     * Normalizes frame options value for reliable comparison in configuration code paths.
     *
     * @return lowercase frame option, defaulting to {@code deny} when value is blank
     */
    public String normalizedFrameOptions() {
        return frameOptions == null ? "deny" : frameOptions.trim().toLowerCase(Locale.ROOT);
    }

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>();
        private List<String> allowedMethods = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");
        private List<String> allowedHeaders = List.of("*");
        private List<String> exposedHeaders = List.of("Authorization", "Content-Type");
        private boolean allowCredentials = true;
        private long maxAgeSeconds = 3600L;
    }
}
