package com.mamoji.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.ai.AiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(prefix = "ai.memory-ops", name = "redis-enabled", havingValue = "true")
public class RedisConversationMemoryService implements ConversationMemoryService {

    private static final String KEY_PREFIX = "ai:memory:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;

    public RedisConversationMemoryService(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        AiProperties aiProperties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
    }

    @Override
    public void append(String sessionKey, String role, String content) {
        if (sessionKey == null || sessionKey.isBlank() || content == null || content.isBlank()) {
            return;
        }

        String key = buildKey(sessionKey);
        int maxStoredTurns = Math.max(1, aiProperties.getMemoryOps().getMaxStoredTurns());
        int ttlSeconds = Math.max(60, aiProperties.getMemoryOps().getTtlSeconds());

        MemoryItem item = new MemoryItem(role, content, Instant.now().toString());
        String payload = toJson(item);

        redisTemplate.opsForList().rightPush(key, payload);
        compactIfNeeded(key, maxStoredTurns);
        redisTemplate.opsForList().trim(key, -maxStoredTurns, -1);
        redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
    }

    @Override
    public List<ConversationTurn> recent(String sessionKey, int maxTurns) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return List.of();
        }

        int safeMaxTurns = Math.max(0, maxTurns);
        if (safeMaxTurns == 0) {
            return List.of();
        }

        List<String> entries = redisTemplate.opsForList().range(buildKey(sessionKey), -safeMaxTurns, -1);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        List<ConversationTurn> turns = new ArrayList<>(entries.size());
        for (String entry : entries) {
            ConversationTurn turn = fromJson(entry);
            if (turn != null) {
                turns.add(turn);
            }
        }
        return turns;
    }

    @Override
    public void clear(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        redisTemplate.delete(buildKey(sessionKey));
    }

    private String buildKey(String sessionKey) {
        return KEY_PREFIX + sessionKey;
    }

    private String toJson(MemoryItem item) {
        try {
            return objectMapper.writeValueAsString(item);
        } catch (Exception ex) {
            return "{\"role\":\"assistant\",\"content\":\"serialization_error\",\"timestamp\":\"" + Instant.now() + "\"}";
        }
    }

    private ConversationTurn fromJson(String value) {
        try {
            MemoryItem item = objectMapper.readValue(value, MemoryItem.class);
            return new ConversationTurn(item.role(), item.content(), Instant.parse(item.timestamp()));
        } catch (Exception ex) {
            return null;
        }
    }

    private void compactIfNeeded(String key, int maxStoredTurns) {
        AiProperties.MemoryOps memoryOps = aiProperties.getMemoryOps();
        if (!memoryOps.isSummarizeOnOverflow()) {
            return;
        }
        Long size = redisTemplate.opsForList().size(key);
        if (size == null || size <= maxStoredTurns) {
            return;
        }

        int batchSize = Math.max(2, memoryOps.getSummarizeBatchSize());
        List<String> oldest = redisTemplate.opsForList().range(key, 0, batchSize - 1);
        if (oldest == null || oldest.isEmpty()) {
            return;
        }

        List<ConversationTurn> turns = new ArrayList<>();
        for (String item : oldest) {
            ConversationTurn turn = fromJson(item);
            if (turn != null) {
                turns.add(turn);
            }
        }
        String summary = summarize(turns);

        redisTemplate.opsForList().trim(key, batchSize, -1);
        redisTemplate.opsForList().leftPush(key, toJson(new MemoryItem("system_summary", summary, Instant.now().toString())));
    }

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

    private record MemoryItem(String role, String content, String timestamp) {
    }
}
