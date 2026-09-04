package com.mamoji.evidence.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mamoji.evidence.domain.ReceiptObjectLocation;
import com.mamoji.evidence.domain.ReceiptStorageAuditReport;
import com.mamoji.platform.scheduling.application.DistributedJobCoordinator;
import com.mamoji.service.support.ObjectStorageService;
import com.mamoji.service.support.ObjectStorageService.StoredObjectMetadata;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReceiptStorageAuditServiceTest {

    @Test
    void auditsCurrentAndHistoricalBucketsAndRecordsLowCardinalityMetrics() {
        ReceiptStorageReferenceRepository references = mock(ReceiptStorageReferenceRepository.class);
        ObjectStorageService objectStorage = mock(ObjectStorageService.class);
        DistributedJobCoordinator jobs = mock(DistributedJobCoordinator.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ReceiptObjectLocation matched = new ReceiptObjectLocation("archive", "receipts/matched.pdf");
        ReceiptObjectLocation missing = new ReceiptObjectLocation("mamoji", "receipts/missing.pdf");
        when(objectStorage.bucketName()).thenReturn("mamoji");
        when(references.findAll("mamoji", 100)).thenReturn(new ReceiptStorageReferenceSnapshot(
            List.of(matched, matched, missing),
            1
        ));
        when(objectStorage.listObjects(eq(Set.of("archive", "mamoji")), eq("receipts/"), eq(100)))
            .thenReturn(List.of(
                new StoredObjectMetadata(
                    "archive", "receipts/matched.pdf", 12, Instant.now().minus(Duration.ofDays(1))
                ),
                new StoredObjectMetadata(
                    "mamoji", "receipts/orphan.pdf", 8, Instant.now().minus(Duration.ofDays(1))
                )
            ));
        ReceiptStorageAuditService service = service(references, objectStorage, jobs, meters, true);

        ReceiptStorageAuditReport report = service.runAudit();

        assertEquals(1, report.orphanCandidateCount());
        assertEquals(1, report.missingObjectCount());
        assertEquals(1, report.invalidReferenceCount());
        assertEquals(1, report.duplicateReferenceCount());
        assertEquals(1.0, counter(meters, "mamoji.receipt.storage.audit.runs", "outcome", "success"));
        assertEquals(1.0, counter(meters, "mamoji.receipt.storage.audit.findings", "type", "orphan"));
        assertEquals(1.0, counter(meters, "mamoji.receipt.storage.audit.findings", "type", "missing"));
    }

    @Test
    void scheduledAuditUsesTheDistributedLease() {
        ReceiptStorageReferenceRepository references = mock(ReceiptStorageReferenceRepository.class);
        ObjectStorageService objectStorage = mock(ObjectStorageService.class);
        DistributedJobCoordinator jobs = mock(DistributedJobCoordinator.class);
        when(objectStorage.isEnabled()).thenReturn(true);
        when(objectStorage.bucketName()).thenReturn("mamoji");
        when(references.findAll("mamoji", 100)).thenReturn(new ReceiptStorageReferenceSnapshot(List.of(), 0));
        when(objectStorage.listObjects(Set.of("mamoji"), "receipts/", 100)).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            return true;
        }).when(jobs).runIfDue(eq("receipt-storage-integrity-audit"), eq(21_600_000L), eq(1_800_000L), any());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        service(references, objectStorage, jobs, meters, true).auditWhenDue();

        verify(jobs).runIfDue(eq("receipt-storage-integrity-audit"), eq(21_600_000L), eq(1_800_000L), any());
        assertEquals(1.0, counter(meters, "mamoji.receipt.storage.audit.runs", "outcome", "success"));
    }

    @Test
    void recordsScheduledFailuresWithoutEscapingTheScheduler() {
        ObjectStorageService objectStorage = mock(ObjectStorageService.class);
        DistributedJobCoordinator jobs = mock(DistributedJobCoordinator.class);
        when(objectStorage.isEnabled()).thenReturn(true);
        doThrow(new IllegalStateException("MinIO unavailable"))
            .when(jobs).runIfDue(any(), anyLong(), anyLong(), any());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        service(mock(ReceiptStorageReferenceRepository.class), objectStorage, jobs, meters, true).auditWhenDue();

        assertEquals(1.0, counter(meters, "mamoji.receipt.storage.audit.runs", "outcome", "failure"));
    }

    @Test
    void disabledAuditDoesNotAcquireALease() {
        ObjectStorageService objectStorage = mock(ObjectStorageService.class);
        ReceiptStorageReferenceRepository references = mock(ReceiptStorageReferenceRepository.class);
        DistributedJobCoordinator jobs = mock(DistributedJobCoordinator.class);

        service(references, objectStorage, jobs, new SimpleMeterRegistry(), false).auditWhenDue();

        verify(jobs, never()).runIfDue(any(), anyLong(), anyLong(), any());
        verifyNoInteractions(references);
    }

    private ReceiptStorageAuditService service(
        ReceiptStorageReferenceRepository references,
        ObjectStorageService objectStorage,
        DistributedJobCoordinator jobs,
        SimpleMeterRegistry meters,
        boolean enabled
    ) {
        return new ReceiptStorageAuditService(
            references,
            objectStorage,
            jobs,
            meters,
            enabled,
            21_600_000,
            1_800_000,
            100,
            3_600_000,
            10
        );
    }

    private double counter(
        SimpleMeterRegistry meters,
        String name,
        String tagName,
        String tagValue
    ) {
        return meters.get(name).tag(tagName, tagValue).counter().count();
    }
}
