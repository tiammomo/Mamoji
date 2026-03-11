package com.mamoji.ai;

import com.mamoji.ai.tool.AiToolGuardService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * Test suite for AiToolGuardServiceTest.
 */

class AiToolGuardServiceTest {

    @Test
    void shouldDenyBlockedTool() {
        AiProperties properties = new AiProperties();
        properties.getToolOps().setEnabled(true);
        properties.getToolOps().setBlockedTools(List.of("stock"));

        AiToolGuardService guardService = new AiToolGuardService(properties);
        AiToolGuardService.GuardDecision decision = guardService.checkAndConsume(1L, "stock");

        Assertions.assertFalse(decision.allowed());
        Assertions.assertEquals("tool_blocked", decision.reason());
    }

    @Test
    void shouldRateLimitAfterConfiguredThreshold() {
        AiProperties properties = new AiProperties();
        properties.getToolOps().setEnabled(true);
        properties.getToolOps().setPerUserToolPerMinute(2);

        AiToolGuardService guardService = new AiToolGuardService(properties);

        Assertions.assertTrue(guardService.checkAndConsume(1L, "finance").allowed());
        Assertions.assertTrue(guardService.checkAndConsume(1L, "finance").allowed());
        AiToolGuardService.GuardDecision denied = guardService.checkAndConsume(1L, "finance");

        Assertions.assertFalse(denied.allowed());
        Assertions.assertEquals("tool_rate_limited", denied.reason());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldCleanupStaleCounterWindows() throws Exception {
        AiProperties properties = new AiProperties();
        properties.getToolOps().setEnabled(true);
        properties.getToolOps().setPerUserToolPerMinute(1000);

        AiToolGuardService guardService = new AiToolGuardService(properties);
        Assertions.assertTrue(guardService.checkAndConsume(1L, "finance").allowed());

        Field countersField = AiToolGuardService.class.getDeclaredField("counters");
        countersField.setAccessible(true);
        Map<String, Object> counters = (Map<String, Object>) countersField.get(guardService);
        Object staleWindow = counters.get("1:finance");

        Field minuteField = staleWindow.getClass().getDeclaredField("minute");
        minuteField.setAccessible(true);
        long nowMinute = java.time.Instant.now().getEpochSecond() / 60;
        minuteField.setLong(staleWindow, nowMinute - 10);

        for (int i = 0; i < 64; i++) {
            guardService.checkAndConsume(2L, "stock");
        }

        Assertions.assertFalse(counters.containsKey("1:finance"));
        Assertions.assertTrue(counters.containsKey("2:stock"));
    }
}



