package com.mamoji.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * Reactive WebClient infrastructure configuration.
 */
@Configuration
@EnableConfigurationProperties(WebClientProperties.class)
public class WebClientConfig {

    private final WebClientProperties properties;

    public WebClientConfig(WebClientProperties properties) {
        this.properties = properties;
    }

    /**
     * Creates a shared {@link WebClient.Builder} backed by tuned Reactor Netty client.
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        ConnectionProvider provider = ConnectionProvider.builder("mamoji-webclient")
            .maxConnections(Math.max(1, properties.getMaxConnections()))
            .pendingAcquireMaxCount(Math.max(1, properties.getPendingAcquireMaxCount()))
            .pendingAcquireTimeout(Duration.ofMillis(Math.max(100, properties.getPendingAcquireTimeoutMs())))
            .maxIdleTime(Duration.ofSeconds(Math.max(1, properties.getMaxIdleSeconds())))
            .maxLifeTime(Duration.ofSeconds(Math.max(1, properties.getMaxLifeSeconds())))
            .build();

        HttpClient httpClient = HttpClient.create(provider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.max(100, properties.getConnectTimeoutMs()))
            .responseTimeout(Duration.ofMillis(Math.max(100, properties.getResponseTimeoutMs())))
            .doOnConnected(connection -> connection
                .addHandlerLast(new ReadTimeoutHandler(Math.max(100, properties.getReadTimeoutMs()), java.util.concurrent.TimeUnit.MILLISECONDS))
                .addHandlerLast(new WriteTimeoutHandler(Math.max(100, properties.getWriteTimeoutMs()), java.util.concurrent.TimeUnit.MILLISECONDS)));

        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
