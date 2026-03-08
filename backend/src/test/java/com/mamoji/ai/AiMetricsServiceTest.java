package com.mamoji.ai;

import com.mamoji.ai.metrics.AiMetricsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

class AiMetricsServiceTest {

    @Test
    void shouldRecordRequestMetricsWithTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(Map.of("meterRegistry", registry));
        AiMetricsService service = new AiMetricsService(beanFactory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));

        service.recordRequest("minimaxi", true, 120, 88);

        double count = registry.get("ai.request.count")
            .tag("provider", "minimaxi")
            .tag("success", "true")
            .counter()
            .count();
        double latencyMs = registry.get("ai.request.latency")
            .tag("provider", "minimaxi")
            .tag("success", "true")
            .timer()
            .totalTime(TimeUnit.MILLISECONDS);
        double tokens = registry.get("ai.request.tokens")
            .tag("provider", "minimaxi")
            .summary()
            .totalAmount();

        Assertions.assertEquals(1.0, count);
        Assertions.assertEquals(120.0, latencyMs);
        Assertions.assertEquals(88.0, tokens);
    }

    @Test
    void shouldClampNegativeEstimatedTokensToZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(Map.of("meterRegistry", registry));
        AiMetricsService service = new AiMetricsService(beanFactory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));

        service.recordRequest("minimaxi", false, 30, -7);

        double tokens = registry.get("ai.request.tokens")
            .tag("provider", "minimaxi")
            .summary()
            .totalAmount();

        Assertions.assertEquals(0.0, tokens);
    }

    @Test
    void shouldRecordToolAndQualityMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(Map.of("meterRegistry", registry));
        AiMetricsService service = new AiMetricsService(beanFactory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));

        service.recordToolCall("finance.query_budget", true, 42);
        service.recordQualityWarnings("finance", 2);

        double toolCount = registry.get("ai.tool.count")
            .tag("tool", "finance.query_budget")
            .tag("success", "true")
            .counter()
            .count();
        double toolLatencyMs = registry.get("ai.tool.latency")
            .tag("tool", "finance.query_budget")
            .tag("success", "true")
            .timer()
            .totalTime(TimeUnit.MILLISECONDS);
        double warnings = registry.get("ai.quality.warnings")
            .tag("assistantType", "finance")
            .summary()
            .totalAmount();

        Assertions.assertEquals(1.0, toolCount);
        Assertions.assertEquals(42.0, toolLatencyMs);
        Assertions.assertEquals(2.0, warnings);
    }

    @Test
    void shouldRecordModelRouteReasonMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(Map.of("meterRegistry", registry));
        AiMetricsService service = new AiMetricsService(beanFactory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));

        service.recordModelRouteReason("finance", "finance-model", "assistant_type_finance");

        double count = registry.get("ai.model.route.reason.count")
            .tag("assistantType", "finance")
            .tag("model", "finance-model")
            .tag("reason", "assistant_type_finance")
            .counter()
            .count();

        Assertions.assertEquals(1.0, count);
    }

    @Test
    void shouldNormalizeHighCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(Map.of("meterRegistry", registry));
        AiMetricsService service = new AiMetricsService(beanFactory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));

        service.recordModelRouteReason("Finance Team #1", "model/very/unexpected", "custom reason");

        double count = registry.get("ai.model.route.reason.count")
            .tag("assistantType", "other")
            .tag("model", "other")
            .tag("reason", "other")
            .counter()
            .count();

        Assertions.assertEquals(1.0, count);
    }

    @Test
    void shouldRecordMissingDimensionMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(Map.of("meterRegistry", registry));
        AiMetricsService service = new AiMetricsService(beanFactory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));

        service.recordRequest(null, true, 15, -1);

        double providerMissing = registry.get("ai.metrics.dimension.missing.count")
            .tag("metric", "ai.request")
            .tag("dimension", "provider")
            .counter()
            .count();
        double tokensMissing = registry.get("ai.metrics.dimension.missing.count")
            .tag("metric", "ai.request")
            .tag("dimension", "estimated_tokens")
            .counter()
            .count();

        Assertions.assertEquals(1.0, providerMissing);
        Assertions.assertEquals(1.0, tokensMissing);
    }
}
