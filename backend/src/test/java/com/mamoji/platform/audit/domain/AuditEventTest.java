package com.mamoji.platform.audit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AuditEventTest {
    @Test
    void normalizesLegacyFallbackValuesAtTheDomainBoundary() {
        AuditEvent event = new AuditEvent(0, " ", 0, null, "", 0, null, "2026-09-01T10:00:00+08:00");

        assertEquals("unknown", event.entityType());
        assertEquals("update", event.action());
        assertEquals("记录更新", event.summary());
        assertEquals("系统用户", event.actorName());
    }

    @Test
    void rejectsInvalidIdentifiersAndMissingCreationTime() {
        assertThrows(IllegalArgumentException.class, () ->
            new AuditEvent(-1, "company", 1, "update", "summary", 1, "actor", "now")
        );
        assertThrows(IllegalArgumentException.class, () ->
            new AuditEvent(1, "company", 1, "update", "summary", 1, "actor", " ")
        );
    }
}
