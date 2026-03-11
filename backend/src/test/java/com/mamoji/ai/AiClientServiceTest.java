package com.mamoji.ai;

import com.mamoji.ai.metrics.AiMetricsService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test suite for AiClientServiceTest.
 */

class AiClientServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldFallbackToBackupModelWhenPrimaryFails() throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/text/chatcompletion_v2", exchange -> {
            String body = readBody(exchange);
            if (body.contains("\"model\":\"primary-model\"")) {
                primaryCalls.incrementAndGet();
                writeResponse(exchange, 500, "{\"error\":{\"message\":\"primary failed\"}}");
                return;
            }
            if (body.contains("\"model\":\"backup-model\"")) {
                fallbackCalls.incrementAndGet();
                writeResponse(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"fallback-ok\"}}]}");
                return;
            }
            writeResponse(exchange, 400, "{\"error\":{\"message\":\"unexpected model\"}}");
        });
        server.start();

        AiClientService service = buildService("primary-model", "backup-model", 0);
        String answer = service.chat("system", "user");

        Assertions.assertEquals("fallback-ok", answer);
        Assertions.assertEquals(1, primaryCalls.get());
        Assertions.assertEquals(1, fallbackCalls.get());
    }

    @Test
    void shouldNotRetryOnClient4xxErrors() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/text/chatcompletion_v2", exchange -> {
            calls.incrementAndGet();
            writeResponse(exchange, 400, "{\"error\":{\"message\":\"bad request\"}}");
        });
        server.start();

        AiClientService service = buildService("primary-model", null, 3);
        String answer = service.chat("system", "user");

        Assertions.assertEquals("Sorry, AI service is temporarily unavailable. Please try again later.", answer);
        Assertions.assertEquals(1, calls.get());
    }

    @Test
    void shouldRetryOnServer5xxErrorsAccordingToMaxRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/text/chatcompletion_v2", exchange -> {
            calls.incrementAndGet();
            writeResponse(exchange, 503, "{\"error\":{\"message\":\"upstream unavailable\"}}");
        });
        server.start();

        AiClientService service = buildService("primary-model", null, 2);
        String answer = service.chat("system", "user");

        Assertions.assertEquals("Sorry, AI service is temporarily unavailable. Please try again later.", answer);
        Assertions.assertEquals(3, calls.get());
    }

    @Test
    void shouldExtractReplyFromChoiceTextShape() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/text/chatcompletion_v2", exchange -> {
            readBody(exchange);
            writeResponse(exchange, 200, "{\"choices\":[{\"text\":\"text-shape-ok\"}]}");
        });
        server.start();

        AiClientService service = buildService("primary-model", null, 0);
        String answer = service.chat("system", "user");

        Assertions.assertEquals("text-shape-ok", answer);
    }

    @Test
    void shouldExtractReplyFromTopLevelOutputShape() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/text/chatcompletion_v2", exchange -> {
            readBody(exchange);
            writeResponse(exchange, 200, "{\"output\":{\"text\":\"output-shape-ok\"}}");
        });
        server.start();

        AiClientService service = buildService("primary-model", null, 0);
        String answer = service.chat("system", "user");

        Assertions.assertEquals("output-shape-ok", answer);
    }

    private AiClientService buildService(String primaryModel, String fallbackModel, int maxRetries) {
        AiProperties properties = new AiProperties();
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setApiKey("test-token");
        properties.setModel(primaryModel);
        properties.setFallbackModel(fallbackModel);
        properties.setTimeoutSeconds(2);
        properties.setMaxRetries(maxRetries);

        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> registryProvider = Mockito.mock(ObjectProvider.class);
        Mockito.when(registryProvider.getIfAvailable()).thenReturn(new SimpleMeterRegistry());
        AiMetricsService metricsService = new AiMetricsService(registryProvider);

        return new AiClientService(properties, WebClient.builder(), metricsService);
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void writeResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}



