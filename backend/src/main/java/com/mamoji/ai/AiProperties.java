package com.mamoji.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /**
     * AI engine selector. Supported values: legacy, spring-ai (default).
     */
    private String engine = "spring-ai";

    /**
     * Provider base URL, e.g. https://api.minimaxi.com
     */
    private String baseUrl = "https://api.minimaxi.com";

    /**
     * API token for the upstream model provider.
     */
    private String apiKey;

    /**
     * Default model name.
     */
    private String model = "abab6.5s-chat";

    /**
     * Optional fallback model used when primary model request fails.
     */
    private String fallbackModel;

    private int maxTokens = 1024;

    private double temperature = 0.7;

    /**
     * Request timeout in seconds.
     */
    private int timeoutSeconds = 30;

    /**
     * Max retry attempts for transient failures.
     */
    private int maxRetries = 2;

    private final PromptOps promptOps = new PromptOps();
    private final ToolOps toolOps = new ToolOps();
    private final ToolCallingOps toolCallingOps = new ToolCallingOps();
    private final StreamOps streamOps = new StreamOps();
    private final ToolExecOps toolExecOps = new ToolExecOps();
    private final MemoryOps memoryOps = new MemoryOps();
    private final RagOps ragOps = new RagOps();
    private final QualityOps qualityOps = new QualityOps();
    private final RoutingOps routingOps = new RoutingOps();

    @Getter
    @Setter
    public static class PromptOps {
        /**
         * Whether prompt experiment is enabled.
         */
        private boolean enabled = true;

        /**
         * Default variant when experiment is disabled.
         */
        private String defaultVariant = "A";

        /**
         * Experiment id for tracking.
         */
        private String experimentId = "prompt-exp-v1";

        /**
         * Percentage(0-100) of finance traffic assigned to variant A.
         */
        private int financeVariantAWeight = 50;

        /**
         * Percentage(0-100) of stock traffic assigned to variant A.
         */
        private int stockVariantAWeight = 50;
    }

    @Getter
    @Setter
    public static class ToolOps {
        /**
         * Whether tool guard (policy + rate limit) is enabled.
         */
        private boolean enabled = true;

        /**
         * Max allowed calls per user+tool in a rolling 1-minute window.
         */
        private int perUserToolPerMinute = 30;

        /**
         * Denied tool names, case-insensitive exact match.
         */
        private List<String> blockedTools = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class ToolCallingOps {
        /**
         * Whether spring-ai tool-calling bridge is enabled.
         */
        private boolean springEnabled = false;

        /**
         * Whether stock domain tool-calling is enabled on spring-ai bridge.
         */
        private boolean stockEnabled = true;
    }

    @Getter
    @Setter
    public static class StreamOps {
        /**
         * Whether /chat/stream uses ReAct/RAG/Tool main pipeline.
         */
        private boolean reactEnabled = true;
    }

    @Getter
    @Setter
    public static class ToolExecOps {
        /**
         * Tool call timeout in milliseconds.
         */
        private long timeoutMs = 3000;

        /**
         * Max concurrent executions for a single tool.
         */
        private int maxConcurrentPerTool = 4;

        /**
         * Open circuit for this duration when failure threshold is reached.
         */
        private int circuitOpenSeconds = 30;

        /**
         * Consecutive failure threshold to open circuit.
         */
        private int failureThreshold = 3;

        /**
         * Core thread pool size for tool execution.
         */
        private int executorCorePoolSize = 8;

        /**
         * Max thread pool size for tool execution.
         */
        private int executorMaxPoolSize = 32;

        /**
         * Queue capacity for tool execution tasks.
         */
        private int executorQueueCapacity = 256;

        /**
         * Max idempotent cache entries for tool execution.
         */
        private int cacheMaxEntries = 2000;

        /**
         * Idempotent cache TTL in seconds.
         */
        private int cacheTtlSeconds = 60;
    }

    @Getter
    @Setter
    public static class MemoryOps {
        /**
         * Use Redis-backed memory store instead of in-memory map.
         */
        private boolean redisEnabled = false;

        /**
         * Max turns kept per session.
         */
        private int maxStoredTurns = 40;

        /**
         * Session TTL in seconds for Redis memory.
         */
        private int ttlSeconds = 86400;

        /**
         * Compress oldest turns into one summary item when size exceeds threshold.
         */
        private boolean summarizeOnOverflow = true;

        /**
         * Number of oldest turns to compact into one summary chunk.
         */
        private int summarizeBatchSize = 8;
    }

    @Getter
    @Setter
    public static class RagOps {
        /**
         * Whether to enable file-based knowledge retrieval.
         */
        private boolean fileEnabled = true;

        /**
         * Knowledge file path, supports classpath: and file:.
         */
        private String knowledgePath = "classpath:ai/knowledge-base.json";
    }

    @Getter
    @Setter
    public static class QualityOps {
        /**
         * Minimal answer length before "too_short" warning.
         */
        private int minAnswerLength = 10;

        /**
         * Max user question length before warning.
         */
        private int maxQuestionLength = 2000;

        /**
         * Require risk disclaimer for stock responses.
         */
        private boolean stockRiskRequired = true;

        /**
         * Require actionable hint for finance responses.
         */
        private boolean financeActionableRequired = true;

        /**
         * Require explicit recency statement in answers.
         */
        private boolean recencyStatementRequired = false;
    }

    @Getter
    @Setter
    public static class RoutingOps {
        /**
         * Enable model routing based on assistant type and complexity.
         */
        private boolean enabled = true;

        /**
         * Optional dedicated model for finance assistant.
         */
        private String financeModel;

        /**
         * Optional dedicated model for stock assistant.
         */
        private String stockModel;

        /**
         * Optional model used for high-complexity prompts.
         */
        private String highComplexityModel;

        /**
         * If question length exceeds this threshold, treat as high complexity.
         */
        private int highComplexityQuestionChars = 400;
    }
}
