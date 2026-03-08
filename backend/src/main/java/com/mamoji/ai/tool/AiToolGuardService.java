package com.mamoji.ai.tool;

import com.mamoji.ai.AiProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AiToolGuardService {

    private static final int STALE_WINDOW_MINUTES = 2;
    private static final int CLEANUP_EVERY_CALLS = 64;

    private final AiProperties aiProperties;
    private final Map<String, ToolCounterWindow> counters = new ConcurrentHashMap<>();
    private final AtomicInteger cleanupTicker = new AtomicInteger(0);

    public AiToolGuardService(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    public GuardDecision checkAndConsume(Long userId, String toolName) {
        AiProperties.ToolOps toolOps = aiProperties.getToolOps();
        if (!toolOps.isEnabled()) {
            return GuardDecision.allow();
        }

        String safeToolName = normalize(toolName);
        if (isBlocked(safeToolName, toolOps)) {
            return GuardDecision.deny("tool_blocked");
        }

        int limit = Math.max(1, toolOps.getPerUserToolPerMinute());
        String key = normalizeUser(userId) + ":" + safeToolName;
        long currentMinute = Instant.now().getEpochSecond() / 60;
        cleanupIfNeeded(currentMinute);

        ToolCounterWindow window = counters.computeIfAbsent(key, k -> new ToolCounterWindow(currentMinute));
        synchronized (window) {
            if (window.minute != currentMinute) {
                window.minute = currentMinute;
                window.counter.set(0);
            }
            int count = window.counter.incrementAndGet();
            if (count > limit) {
                window.counter.decrementAndGet();
                return GuardDecision.deny("tool_rate_limited");
            }
            return GuardDecision.allow();
        }
    }

    int counterSizeForTest() {
        return counters.size();
    }

    private void cleanupIfNeeded(long currentMinute) {
        if (cleanupTicker.incrementAndGet() % CLEANUP_EVERY_CALLS != 0) {
            return;
        }
        long minKeptMinute = currentMinute - STALE_WINDOW_MINUTES;
        counters.entrySet().removeIf(entry -> entry.getValue().minute < minKeptMinute);
    }

    private boolean isBlocked(String toolName, AiProperties.ToolOps toolOps) {
        if (toolOps.getBlockedTools() == null || toolOps.getBlockedTools().isEmpty()) {
            return false;
        }
        for (String blocked : toolOps.getBlockedTools()) {
            if (toolName.equals(normalize(blocked))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeUser(Long userId) {
        return userId == null ? "anonymous" : String.valueOf(userId);
    }

    public record GuardDecision(boolean allowed, String reason) {
        public static GuardDecision allow() {
            return new GuardDecision(true, null);
        }

        public static GuardDecision deny(String reason) {
            return new GuardDecision(false, reason);
        }
    }

    private static class ToolCounterWindow {
        private long minute;
        private final AtomicInteger counter = new AtomicInteger();

        private ToolCounterWindow(long minute) {
            this.minute = minute;
        }
    }
}
