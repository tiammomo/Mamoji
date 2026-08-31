package com.mamoji.notification.domain;

/** Durable business event plus its current delivery lease and retry state. */
public class OutboxEvent {
    public long id;
    public String eventId;
    public String eventType;
    public String aggregateType;
    public long aggregateId;
    public long companyId;
    public long actorUserId;
    public String payloadJson;
    public String status;
    public int attempts;
    public String nextAttemptAt;
    public String lockedAt;
    public String lockToken;
    public String processedAt;
    public String lastError;
    public String createdAt;
    public String updatedAt;
}
