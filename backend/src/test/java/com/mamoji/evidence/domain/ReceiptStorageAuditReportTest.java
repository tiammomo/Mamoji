package com.mamoji.evidence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiptStorageAuditReportTest {
    private static final Instant SCANNED_AT = Instant.parse("2026-06-01T12:00:00Z");

    @Test
    void reconcilesReferencesWithOldAndInFlightObjects() {
        ReceiptObjectLocation matched = location("archive", "receipts/company-1/matched.pdf");
        ReceiptObjectLocation missing = location("mamoji", "receipts/company-1/missing.pdf");
        ReceiptObjectLocation orphan = location("mamoji", "receipts/company-2/orphan.pdf");
        ReceiptObjectLocation young = location("mamoji", "receipts/company-3/uploading.pdf");

        ReceiptStorageAuditReport report = ReceiptStorageAuditReport.reconcile(
            List.of(matched, matched, missing),
            List.of(
                stored(matched, 100, SCANNED_AT.minus(Duration.ofDays(1))),
                stored(orphan, 200, SCANNED_AT.minus(Duration.ofHours(1))),
                stored(young, 300, SCANNED_AT.minus(Duration.ofMinutes(59)))
            ),
            1,
            SCANNED_AT,
            Duration.ofHours(1),
            10
        );

        assertEquals(3, report.validReferenceCount());
        assertEquals(2, report.distinctReferenceCount());
        assertEquals(3, report.storedObjectCount());
        assertEquals(600, report.storedBytes());
        assertEquals(1, report.orphanCandidateCount());
        assertEquals(1, report.youngUnreferencedCount());
        assertEquals(1, report.missingObjectCount());
        assertEquals(1, report.invalidReferenceCount());
        assertEquals(1, report.duplicateReferenceCount());
        assertEquals(List.of(orphan), report.orphanSamples());
        assertEquals(List.of(missing), report.missingSamples());
        assertTrue(report.hasActionableFindings());
    }

    @Test
    void sortsAndBoundsDiagnosticSamples() {
        ReceiptObjectLocation first = location("a", "receipts/1.pdf");
        ReceiptObjectLocation second = location("b", "receipts/2.pdf");

        ReceiptStorageAuditReport report = ReceiptStorageAuditReport.reconcile(
            List.of(),
            List.of(
                stored(second, 1, SCANNED_AT.minus(Duration.ofHours(2))),
                stored(first, 1, SCANNED_AT.minus(Duration.ofHours(2)))
            ),
            0,
            SCANNED_AT,
            Duration.ofHours(1),
            1
        );

        assertEquals(List.of(first), report.orphanSamples());
    }

    @Test
    void rejectsDuplicateObjectInventoryAndByteOverflow() {
        ReceiptObjectLocation location = location("mamoji", "receipts/1.pdf");

        assertThrows(IllegalArgumentException.class, () -> ReceiptStorageAuditReport.reconcile(
            List.of(),
            List.of(stored(location, 1, SCANNED_AT), stored(location, 2, SCANNED_AT)),
            0,
            SCANNED_AT,
            Duration.ZERO,
            10
        ));
        assertThrows(ArithmeticException.class, () -> ReceiptStorageAuditReport.reconcile(
            List.of(),
            List.of(
                stored(location, Long.MAX_VALUE, SCANNED_AT),
                stored(location("mamoji", "receipts/2.pdf"), 1, SCANNED_AT)
            ),
            0,
            SCANNED_AT,
            Duration.ZERO,
            10
        ));
    }

    private ReceiptObjectLocation location(String bucket, String key) {
        return new ReceiptObjectLocation(bucket, key);
    }

    private ReceiptStoredObject stored(ReceiptObjectLocation location, long size, Instant lastModified) {
        return new ReceiptStoredObject(location, size, lastModified);
    }
}
