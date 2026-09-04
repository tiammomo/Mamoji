package com.mamoji.platform.audit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mamoji.platform.audit.domain.AuditEvent;
import com.mamoji.platform.audit.domain.AuditLog;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditTrailServiceTest {
    @Test
    void recordsTimestampedAuditEventsThroughTheAppendOnlyRepository() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        AuditLog saved = new AuditLog(11, 7, "transaction", 9, "create", "created", 3, "Owner", "saved");
        when(repository.append(any())).thenReturn(saved);
        AuditTrailService service = new AuditTrailService(repository);

        AuditLog result = service.record(7, "transaction", 9, "create", "created", 3, "Owner");

        assertSame(saved, result);
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).append(event.capture());
        assertEquals(7, event.getValue().companyId());
        assertEquals("transaction", event.getValue().entityType());
        assertEquals(9, event.getValue().entityId());
        assertEquals("create", event.getValue().action());
        assertEquals("created", event.getValue().summary());
        assertEquals(3, event.getValue().actorUserId());
        assertEquals("Owner", event.getValue().actorName());
        OffsetDateTime.parse(event.getValue().createdAt());
    }

    @Test
    void delegatesEntityHistoryAndExistenceQueries() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        AuditLog log = new AuditLog(11, 7, "receipt_voucher", 9, "seed", "created", 3, "Owner", "now");
        when(repository.findByEntity(7, "receipt_voucher", 9)).thenReturn(List.of(log));
        when(repository.existsByEntityType("receipt_voucher")).thenReturn(true);
        AuditTrailService service = new AuditTrailService(repository);

        assertEquals(List.of(log), service.findByEntity(7, "receipt_voucher", 9));
        assertTrue(service.existsByEntityType("receipt_voucher"));
    }
}
