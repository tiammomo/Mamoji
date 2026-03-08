package com.mamoji.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    private boolean h2ConsoleEnabled = false;

    /**
     * Supported values: deny, sameorigin, disable.
     */
    private String frameOptions = "deny";

    private final Cors cors = new Cors();

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
