package com.mamoji.ai.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class AiMetricsService {

    private static final int MAX_TAG_LENGTH = 48;
    private static final Set<String> ASSISTANT_TYPES = Set.of("finance", "stock", "general", "unknown", "other");
    private static final Set<String> ROUTING_REASONS = Set.of(
        "routing_disabled",
        "high_complexity",
        "assistant_type_stock",
        "assistant_type_finance",
        "default",
        "unknown",
        "other"
    );

    private final MeterRegistry meterRegistry;

    public AiMetricsService(ObjectProvider<MeterRegistry> registryProvider) {
        this.meterRegistry = registryProvider.getIfAvailable();
    }

    public void recordRequest(String provider, boolean success, long latencyMs, int estimatedTokens) {
        if (meterRegistry == null) {
            return;
        }
        String providerTag = normalizeProvider(provider);
        if ("unknown".equals(providerTag)) {
            recordMissingDimension("ai.request", "provider");
        }
        if (estimatedTokens < 0) {
            recordMissingDimension("ai.request", "estimated_tokens");
        }

        Timer.builder("ai.request.latency")
            .tag("provider", providerTag)
            .tag("success", String.valueOf(success))
            .register(meterRegistry)
            .record(Math.max(0, latencyMs), TimeUnit.MILLISECONDS);

        Counter.builder("ai.request.count")
            .tag("provider", providerTag)
            .tag("success", String.valueOf(success))
            .register(meterRegistry)
            .increment();

        DistributionSummary.builder("ai.request.tokens")
            .tag("provider", providerTag)
            .register(meterRegistry)
            .record(Math.max(0, estimatedTokens));
    }

    public void recordModelRoute(String assistantType, String modelName) {
        if (meterRegistry == null) {
            return;
        }
        String assistantTag = normalizeAssistantType(assistantType);
        String modelTag = normalizeGenericTag(modelName);
        if ("unknown".equals(modelTag)) {
            recordMissingDimension("ai.model.route", "model");
        }
        Counter.builder("ai.model.route.count")
            .tag("assistantType", assistantTag)
            .tag("model", modelTag)
            .register(meterRegistry)
            .increment();
    }

    public void recordModelRouteReason(String assistantType, String modelName, String reason) {
        if (meterRegistry == null) {
            return;
        }
        String assistantTag = normalizeAssistantType(assistantType);
        String modelTag = normalizeGenericTag(modelName);
        String reasonTag = normalizeRoutingReason(reason);
        if ("unknown".equals(modelTag)) {
            recordMissingDimension("ai.model.route.reason", "model");
        }
        Counter.builder("ai.model.route.reason.count")
            .tag("assistantType", assistantTag)
            .tag("model", modelTag)
            .tag("reason", reasonTag)
            .register(meterRegistry)
            .increment();
    }

    public void recordModelFallback(String primaryModel, String fallbackModel) {
        if (meterRegistry == null) {
            return;
        }
        String primaryTag = normalizeGenericTag(primaryModel);
        String fallbackTag = normalizeGenericTag(fallbackModel);
        if ("unknown".equals(primaryTag)) {
            recordMissingDimension("ai.model.fallback", "primary");
        }
        if ("unknown".equals(fallbackTag)) {
            recordMissingDimension("ai.model.fallback", "fallback");
        }
        Counter.builder("ai.model.fallback.count")
            .tag("primary", primaryTag)
            .tag("fallback", fallbackTag)
            .register(meterRegistry)
            .increment();
    }

    public void recordToolCall(String toolName, boolean success, long latencyMs) {
        if (meterRegistry == null) {
            return;
        }
        String toolTag = normalizeGenericTag(toolName);
        if ("unknown".equals(toolTag)) {
            recordMissingDimension("ai.tool", "tool");
        }

        Timer.builder("ai.tool.latency")
            .tag("tool", toolTag)
            .tag("success", String.valueOf(success))
            .register(meterRegistry)
            .record(Math.max(0, latencyMs), TimeUnit.MILLISECONDS);

        Counter.builder("ai.tool.count")
            .tag("tool", toolTag)
            .tag("success", String.valueOf(success))
            .register(meterRegistry)
            .increment();
    }

    public void recordQualityWarnings(String assistantType, int warningCount) {
        if (meterRegistry == null) {
            return;
        }
        String assistantTag = normalizeAssistantType(assistantType);
        if ("unknown".equals(assistantTag)) {
            recordMissingDimension("ai.quality", "assistantType");
        }

        DistributionSummary.builder("ai.quality.warnings")
            .tag("assistantType", assistantTag)
            .register(meterRegistry)
            .record(Math.max(0, warningCount));
    }

    public void recordQualityRuleHit(String assistantType, String rule) {
        if (meterRegistry == null) {
            return;
        }
        String assistantTag = normalizeAssistantType(assistantType);
        String ruleTag = normalizeGenericTag(rule);
        if ("unknown".equals(ruleTag)) {
            recordMissingDimension("ai.quality.rule", "rule");
        }
        Counter.builder("ai.quality.rule.hit")
            .tag("assistantType", assistantTag)
            .tag("rule", ruleTag)
            .register(meterRegistry)
            .increment();
    }

    public void recordChatMode(String modeRequested, String modeUsed, String assistantType) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("ai.chat.mode.count")
            .tag("requested", normalizeGenericTag(modeRequested))
            .tag("used", normalizeGenericTag(modeUsed))
            .tag("assistantType", normalizeAssistantType(assistantType))
            .register(meterRegistry)
            .increment();
    }

    public void recordChatModeFallback(String fromMode, String toMode, String reason) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("ai.chat.mode.fallback.count")
            .tag("from", normalizeGenericTag(fromMode))
            .tag("to", normalizeGenericTag(toMode))
            .tag("reason", normalizeGenericTag(reason))
            .register(meterRegistry)
            .increment();
    }

    public void recordCacheAccess(String layer, String cacheName, boolean hit) {
        if (meterRegistry == null) {
            return;
        }
        String layerTag = normalizeGenericTag(layer);
        String cacheTag = normalizeGenericTag(cacheName);
        if ("unknown".equals(cacheTag)) {
            recordMissingDimension("ai.cache.access", "cache");
        }

        Counter.builder("ai.cache.access.count")
            .tag("layer", layerTag)
            .tag("cache", cacheTag)
            .tag("hit", String.valueOf(hit))
            .register(meterRegistry)
            .increment();
    }

    private void recordMissingDimension(String metric, String dimension) {
        Counter.builder("ai.metrics.dimension.missing.count")
            .tag("metric", metric)
            .tag("dimension", dimension)
            .register(meterRegistry)
            .increment();
    }

    private String normalizeProvider(String value) {
        String normalized = normalizeGenericTag(value);
        if ("unknown".equals(normalized)) {
            return normalized;
        }
        return switch (normalized) {
            case "minimaxi", "openai", "anthropic", "azure_openai", "azure-openai" -> normalized;
            default -> "other";
        };
    }

    private String normalizeAssistantType(String value) {
        String normalized = normalizeGenericTag(value);
        return ASSISTANT_TYPES.contains(normalized) ? normalized : "other";
    }

    private String normalizeRoutingReason(String value) {
        String normalized = normalizeGenericTag(value);
        return ROUTING_REASONS.contains(normalized) ? normalized : "other";
    }

    private String normalizeGenericTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_TAG_LENGTH) {
            return "other";
        }
        if (!normalized.matches("[a-z0-9._-]+")) {
            return "other";
        }
        return normalized;
    }
}
