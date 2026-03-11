package com.mamoji.ai.memory;

import com.mamoji.ai.AiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process memory implementation for conversation turns.
 */
@Service
@ConditionalOnProperty(prefix = "ai.memory-ops", name = "redis-enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryConversationMemoryService implements ConversationMemoryService {

    private final Map<String, Deque<ConversationTurn>> sessions = new ConcurrentHashMap<>();
    private final AiProperties aiProperties;

    public InMemoryConversationMemoryService(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    /**
     * Appends one turn and compacts/limits stored turns.
     */
    @Override
    public void append(String sessionKey, String role, String content) {
        if (sessionKey == null || sessionKey.isBlank() || content == null || content.isBlank()) {
            return;
        }
        Deque<ConversationTurn> turns = sessions.computeIfAbsent(sessionKey, key -> new ArrayDeque<>());
        synchronized (turns) {
            turns.addLast(new ConversationTurn(role, content, Instant.now()));
            compactIfNeeded(turns);
            int maxStoredTurns = Math.max(2, aiProperties.getMemoryOps().getMaxStoredTurns());
            while (turns.size() > maxStoredTurns) {
                turns.removeFirst();
            }
        }
    }

    /**
     * Reads recent turns from in-memory session buffer.
     */
    @Override
    public List<ConversationTurn> recent(String sessionKey, int maxTurns) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return List.of();
        }
        Deque<ConversationTurn> turns = sessions.get(sessionKey);
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        synchronized (turns) {
            int size = Math.min(Math.max(maxTurns, 0), turns.size());
            List<ConversationTurn> all = new ArrayList<>(turns);
            return all.subList(all.size() - size, all.size());
        }
    }

    /**
     * Removes one session from memory map.
     */
    @Override
    public void clear(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        sessions.remove(sessionKey);
    }

    /**
     * Compacts oldest turns into a synthetic summary turn when overflowed.
     */
    private void compactIfNeeded(Deque<ConversationTurn> turns) {
        AiProperties.MemoryOps memoryOps = aiProperties.getMemoryOps();
        if (!memoryOps.isSummarizeOnOverflow()) {
            return;
        }
        int maxStoredTurns = Math.max(2, memoryOps.getMaxStoredTurns());
        if (turns.size() <= maxStoredTurns) {
            return;
        }

        int batchSize = Math.max(2, memoryOps.getSummarizeBatchSize());
        List<ConversationTurn> compactBatch = new ArrayList<>();
        for (int i = 0; i < batchSize && !turns.isEmpty(); i++) {
            compactBatch.add(turns.removeFirst());
        }
        turns.addFirst(new ConversationTurn("system_summary", summarize(compactBatch), Instant.now()));
    }

    /**
     * Builds compact summary text from old turns.
     */
    private String summarize(List<ConversationTurn> turns) {
        StringBuilder sb = new StringBuilder("Summary:");
        int max = Math.min(6, turns.size());
        for (int i = 0; i < max; i++) {
            ConversationTurn turn = turns.get(i);
            sb.append(" [").append(turn.role()).append("] ");
            String content = turn.content() == null ? "" : turn.content().replaceAll("\\s+", " ").trim();
            if (content.length() > 40) {
                content = content.substring(0, 40) + "...";
            }
            sb.append(content);
        }
        return sb.toString();
    }
}
