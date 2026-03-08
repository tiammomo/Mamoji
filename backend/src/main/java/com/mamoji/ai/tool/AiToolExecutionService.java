package com.mamoji.ai.tool;

import com.mamoji.ai.AiProperties;
import com.mamoji.ai.metrics.AiMetricsService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AiToolExecutionService {

    private static final long MIN_TIMEOUT_MS = 200L;
    private static final int CACHE_CLEANUP_EVERY_REQUESTS = 64;

    private final ExecutorService executor;
    private final Map<String, Semaphore> semaphores = new ConcurrentHashMap<>();
    private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();
    private final Map<String, CachedResult> idempotentCache = new ConcurrentHashMap<>();
    private final AtomicLong cacheCleanupTicker = new AtomicLong(0);
    private final AiProperties aiProperties;
    private final AiMetricsService aiMetricsService;

    public AiToolExecutionService(AiProperties aiProperties, AiMetricsService aiMetricsService) {
        this.aiProperties = aiProperties;
        this.aiMetricsService = aiMetricsService;
        this.executor = buildExecutor(aiProperties);
    }

    public AiToolResult execute(AiToolHandler handler, Long userId, Map<String, Object> params) {
        String tool = handler.name();
        long now = System.currentTimeMillis();
        cleanupCacheIfNeeded(now);

        String requestKey = buildRequestKey(tool, userId, params);
        CachedResult cached = idempotentCache.get(requestKey);
        if (cached != null && cached.expireAtMs > now) {
            aiMetricsService.recordCacheAccess("tool", tool, true);
            return cached.result;
        }
        if (cached != null) {
            idempotentCache.remove(requestKey, cached);
        }
        aiMetricsService.recordCacheAccess("tool", tool, false);

        CircuitState circuitState = circuits.computeIfAbsent(tool, k -> new CircuitState());
        if (circuitState.openUntilMs > now) {
            return AiToolResult.fail(tool, "tool_circuit_open");
        }

        Semaphore semaphore = semaphores.computeIfAbsent(
            tool,
            k -> new Semaphore(Math.max(1, aiProperties.getToolExecOps().getMaxConcurrentPerTool()))
        );
        if (!semaphore.tryAcquire()) {
            return AiToolResult.fail(tool, "tool_concurrency_limited");
        }

        Future<AiToolResult> future = null;
        try {
            future = executor.submit(() -> handler.execute(userId, params));
            long timeout = Math.max(MIN_TIMEOUT_MS, aiProperties.getToolExecOps().getTimeoutMs());
            AiToolResult result = future.get(timeout, TimeUnit.MILLISECONDS);
            resetCircuit(circuitState);
            putCache(requestKey, result);
            return result;
        } catch (TimeoutException ex) {
            if (future != null) {
                future.cancel(true);
            }
            markFailure(circuitState);
            return AiToolResult.fail(tool, "tool_timeout");
        } catch (RejectedExecutionException ex) {
            return AiToolResult.fail(tool, "tool_executor_rejected");
        } catch (ExecutionException ex) {
            markFailure(circuitState);
            Throwable cause = ex.getCause();
            return AiToolResult.fail(tool, cause != null && cause.getMessage() != null ? cause.getMessage() : "tool_execution_failed");
        } catch (Exception ex) {
            markFailure(circuitState);
            return AiToolResult.fail(tool, ex.getMessage() != null ? ex.getMessage() : "tool_execution_failed");
        } finally {
            semaphore.release();
        }
    }

    private void markFailure(CircuitState state) {
        int threshold = Math.max(1, aiProperties.getToolExecOps().getFailureThreshold());
        int openSeconds = Math.max(1, aiProperties.getToolExecOps().getCircuitOpenSeconds());
        if (state.failures.incrementAndGet() >= threshold) {
            state.openUntilMs = System.currentTimeMillis() + (openSeconds * 1000L);
            state.failures.set(0);
        }
    }

    private void resetCircuit(CircuitState state) {
        state.failures.set(0);
        state.openUntilMs = 0L;
    }

    private ExecutorService buildExecutor(AiProperties properties) {
        AiProperties.ToolExecOps ops = properties.getToolExecOps();
        int corePoolSize = Math.max(2, ops.getExecutorCorePoolSize());
        int maxPoolSize = Math.max(corePoolSize, ops.getExecutorMaxPoolSize());
        int queueCapacity = Math.max(16, ops.getExecutorQueueCapacity());
        return new ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private void putCache(String requestKey, AiToolResult result) {
        AiProperties.ToolExecOps ops = aiProperties.getToolExecOps();
        long ttlMs = Math.max(1, ops.getCacheTtlSeconds()) * 1000L;
        idempotentCache.put(requestKey, new CachedResult(result, System.currentTimeMillis() + ttlMs));

        int maxEntries = Math.max(64, ops.getCacheMaxEntries());
        if (idempotentCache.size() <= maxEntries) {
            return;
        }
        evictOneEntry();
    }

    private void cleanupCacheIfNeeded(long now) {
        long tick = cacheCleanupTicker.incrementAndGet();
        if (tick % CACHE_CLEANUP_EVERY_REQUESTS != 0) {
            return;
        }
        idempotentCache.entrySet().removeIf(entry -> entry.getValue().expireAtMs <= now);
    }

    private void evictOneEntry() {
        String firstKey = idempotentCache.keySet().stream().findFirst().orElse(null);
        if (firstKey != null) {
            idempotentCache.remove(firstKey);
        }
    }

    @PreDestroy
    public void shutdownExecutor() {
        executor.shutdownNow();
    }

    private static class CircuitState {
        private final AtomicInteger failures = new AtomicInteger();
        private volatile long openUntilMs = 0L;
    }

    private String buildRequestKey(String tool, Long userId, Map<String, Object> params) {
        int paramHash = params == null ? 0 : params.toString().hashCode();
        return tool + ":" + (userId == null ? "anonymous" : userId) + ":" + paramHash;
    }

    private record CachedResult(AiToolResult result, long expireAtMs) {
    }
}
