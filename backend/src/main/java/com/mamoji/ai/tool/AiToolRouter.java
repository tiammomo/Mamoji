package com.mamoji.ai.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Routes tool calls to concrete handlers with guard checks and execution tracing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiToolRouter {

    private final AiToolRegistry toolRegistry;
    private final AiToolGuardService toolGuardService;
    private final AiToolExecutionService executionService;

    /**
     * Routes one tool request and returns a standardized result envelope.
     */
    public AiToolResult route(Long userId, String toolName, Map<String, Object> params) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        long start = System.currentTimeMillis();

        if (toolName == null || toolName.isBlank()) {
            return AiToolResult.fail("unknown", "tool name is empty");
        }

        AiToolGuardService.GuardDecision guardDecision = toolGuardService.checkAndConsume(userId, toolName);
        if (!guardDecision.allowed()) {
            log.warn("AI tool denied traceId={} tool={} userId={} reason={}", traceId, toolName, userId, guardDecision.reason());
            return AiToolResult.fail(toolName, guardDecision.reason());
        }

        return toolRegistry.find(toolName)
            .map(handler -> executeHandler(handler, userId, params, traceId, start))
            .orElseGet(() -> {
                log.warn("AI tool missing traceId={} tool={} available={}", traceId, toolName, toolRegistry.allNames());
                return AiToolResult.fail(toolName, "tool not found");
            });
    }

    /**
     * Executes the chosen handler and records success/failure logs.
     */
    private AiToolResult executeHandler(
        AiToolHandler handler,
        Long userId,
        Map<String, Object> params,
        String traceId,
        long start
    ) {
        try {
            AiToolResult result = executionService.execute(handler, userId, params);
            long elapsed = System.currentTimeMillis() - start;
            log.info(
                "AI tool done traceId={} tool={} userId={} elapsedMs={} success={}",
                traceId,
                handler.name(),
                userId,
                elapsed,
                result.success()
            );
            return result;
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.error(
                "AI tool failed traceId={} tool={} userId={} elapsedMs={} error={}",
                traceId,
                handler.name(),
                userId,
                elapsed,
                ex.getMessage(),
                ex
            );
            return AiToolResult.fail(handler.name(), ex.getMessage());
        }
    }
}
