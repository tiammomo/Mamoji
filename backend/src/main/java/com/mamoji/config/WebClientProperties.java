package com.mamoji.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable connection/timeout settings for shared reactive WebClient.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.web-client")
public class WebClientProperties {

    private int maxConnections = 200;
    private int pendingAcquireMaxCount = 500;
    private long pendingAcquireTimeoutMs = 2000;
    private int connectTimeoutMs = 3000;
    private long responseTimeoutMs = 10000;
    private long readTimeoutMs = 10000;
    private long writeTimeoutMs = 10000;
    private long maxIdleSeconds = 30;
    private long maxLifeSeconds = 300;
}
