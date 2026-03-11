package com.mamoji.ai;

import com.mamoji.ai.tool.AiToolExecutionService;
import com.mamoji.ai.tool.AiToolHandler;
import com.mamoji.ai.tool.AiToolResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Test suite for AiToolExecutionServiceTest.
 */

class AiToolExecutionServiceTest {

    @Test
    void shouldTimeoutSlowTool() {
        AiProperties properties = new AiProperties();
        properties.getToolExecOps().setTimeoutMs(100);
        properties.getToolExecOps().setFailureThreshold(1);
        properties.getToolExecOps().setCircuitOpenSeconds(1);
        AiToolExecutionService service = new AiToolExecutionService(properties, Mockito.mock(com.mamoji.ai.metrics.AiMetricsService.class));

        AiToolHandler slow = new AiToolHandler() {
            @Override
            public String name() {
                return "slowTool";
            }

            @Override
            public AiToolResult execute(Long userId, Map<String, Object> params) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
                return AiToolResult.ok(name(), "{}");
            }
        };

        AiToolResult result = service.execute(slow, 1L, Map.of("k", "v"));
        Assertions.assertFalse(result.success());
        Assertions.assertEquals("tool_timeout", result.error());
    }

    @Test
    void shouldReturnCachedResultForSameRequest() {
        AiProperties properties = new AiProperties();
        properties.getToolExecOps().setTimeoutMs(500);
        properties.getToolExecOps().setCacheTtlSeconds(60);
        AiToolExecutionService service = new AiToolExecutionService(properties, Mockito.mock(com.mamoji.ai.metrics.AiMetricsService.class));

        final int[] calls = {0};
        AiToolHandler handler = new AiToolHandler() {
            @Override
            public String name() {
                return "cacheTool";
            }

            @Override
            public AiToolResult execute(Long userId, Map<String, Object> params) {
                calls[0]++;
                return AiToolResult.ok(name(), "{\"n\":" + calls[0] + "}");
            }
        };

        AiToolResult first = service.execute(handler, 2L, Map.of("x", 1));
        AiToolResult second = service.execute(handler, 2L, Map.of("x", 1));

        Assertions.assertTrue(first.success());
        Assertions.assertTrue(second.success());
        Assertions.assertEquals(first.payload(), second.payload());
        Assertions.assertEquals(1, calls[0]);
    }

    @Test
    void shouldExpireCachedResultAfterTtl() throws InterruptedException {
        AiProperties properties = new AiProperties();
        properties.getToolExecOps().setTimeoutMs(500);
        properties.getToolExecOps().setCacheTtlSeconds(1);
        AiToolExecutionService service = new AiToolExecutionService(properties, Mockito.mock(com.mamoji.ai.metrics.AiMetricsService.class));

        final int[] calls = {0};
        AiToolHandler handler = new AiToolHandler() {
            @Override
            public String name() {
                return "ttlTool";
            }

            @Override
            public AiToolResult execute(Long userId, Map<String, Object> params) {
                calls[0]++;
                return AiToolResult.ok(name(), "{\"n\":" + calls[0] + "}");
            }
        };

        AiToolResult first = service.execute(handler, 1L, Map.of("k", "v"));
        Thread.sleep(1200L);
        AiToolResult second = service.execute(handler, 1L, Map.of("k", "v"));

        Assertions.assertNotEquals(first.payload(), second.payload());
        Assertions.assertEquals(2, calls[0]);
    }

    @Test
    void shouldEnforcePerToolConcurrencyLimit() throws Exception {
        AiProperties properties = new AiProperties();
        properties.getToolExecOps().setTimeoutMs(1000);
        properties.getToolExecOps().setMaxConcurrentPerTool(1);
        AiToolExecutionService service = new AiToolExecutionService(properties, Mockito.mock(com.mamoji.ai.metrics.AiMetricsService.class));

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AiToolHandler blockingHandler = new AiToolHandler() {
            @Override
            public String name() {
                return "blockingTool";
            }

            @Override
            public AiToolResult execute(Long userId, Map<String, Object> params) {
                entered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                return AiToolResult.ok(name(), "{}");
            }
        };

        var singleThread = Executors.newSingleThreadExecutor();
        try {
            singleThread.submit(() -> service.execute(blockingHandler, 1L, Map.of("x", 1)));
            Assertions.assertTrue(entered.await(1, TimeUnit.SECONDS));

            AiToolResult denied = service.execute(blockingHandler, 1L, Map.of("x", 1));
            Assertions.assertFalse(denied.success());
            Assertions.assertEquals("tool_concurrency_limited", denied.error());
        } finally {
            release.countDown();
            singleThread.shutdownNow();
        }
    }
}



