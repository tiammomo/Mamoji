package com.mamoji.evidence.application;

import com.mamoji.evidence.domain.ReceiptObjectLocation;
import com.mamoji.evidence.domain.ReceiptStorageAuditReport;
import com.mamoji.evidence.domain.ReceiptStoredObject;
import com.mamoji.platform.scheduling.application.DistributedJobCoordinator;
import com.mamoji.service.support.ObjectStorageService;
import com.mamoji.service.support.ObjectStorageService.StoredObjectMetadata;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Reconciles durable receipt references with MinIO without mutating either system. */
@Service
public class ReceiptStorageAuditService {
    private static final Logger log = LoggerFactory.getLogger(ReceiptStorageAuditService.class);
    private static final String JOB_NAME = "receipt-storage-integrity-audit";
    private static final String RECEIPT_PREFIX = "receipts/";

    private final ReceiptStorageReferenceRepository references;
    private final ObjectStorageService objectStorage;
    private final DistributedJobCoordinator scheduledJobs;
    private final boolean enabled;
    private final long cadenceMillis;
    private final long leaseMillis;
    private final int maximumObjects;
    private final Duration orphanGracePeriod;
    private final int sampleSize;
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter orphanCounter;
    private final Counter missingCounter;
    private final Counter invalidReferenceCounter;
    private final Counter duplicateReferenceCounter;

    public ReceiptStorageAuditService(
        ReceiptStorageReferenceRepository references,
        ObjectStorageService objectStorage,
        DistributedJobCoordinator scheduledJobs,
        MeterRegistry meterRegistry,
        @Value("${mamoji.object-storage.integrity-audit.enabled:false}") boolean enabled,
        @Value("${mamoji.object-storage.integrity-audit.cadence-ms:21600000}") long cadenceMillis,
        @Value("${mamoji.object-storage.integrity-audit.lease-ms:1800000}") long leaseMillis,
        @Value("${mamoji.object-storage.integrity-audit.max-objects:100000}") int maximumObjects,
        @Value("${mamoji.object-storage.integrity-audit.orphan-grace-ms:3600000}") long orphanGraceMillis,
        @Value("${mamoji.object-storage.integrity-audit.sample-size:10}") int sampleSize
    ) {
        this.references = references;
        this.objectStorage = objectStorage;
        this.scheduledJobs = scheduledJobs;
        this.enabled = enabled;
        this.cadenceMillis = atLeastMinute("Receipt storage audit cadence", cadenceMillis);
        this.leaseMillis = atLeastMinute("Receipt storage audit lease", leaseMillis);
        if (maximumObjects <= 0 || maximumObjects > 1_000_000) {
            throw new IllegalArgumentException("Receipt storage audit maximum objects must be between 1 and 1000000");
        }
        this.maximumObjects = maximumObjects;
        this.orphanGracePeriod = Duration.ofMillis(atLeastMinute(
            "Receipt storage audit orphan grace period",
            orphanGraceMillis
        ));
        if (sampleSize <= 0 || sampleSize > 100) {
            throw new IllegalArgumentException("Receipt storage audit sample size must be between 1 and 100");
        }
        this.sampleSize = sampleSize;
        this.successCounter = meterRegistry.counter("mamoji.receipt.storage.audit.runs", "outcome", "success");
        this.failureCounter = meterRegistry.counter("mamoji.receipt.storage.audit.runs", "outcome", "failure");
        this.orphanCounter = findingCounter(meterRegistry, "orphan");
        this.missingCounter = findingCounter(meterRegistry, "missing");
        this.invalidReferenceCounter = findingCounter(meterRegistry, "invalid_reference");
        this.duplicateReferenceCounter = findingCounter(meterRegistry, "duplicate_reference");
    }

    @Scheduled(
        fixedDelayString = "${mamoji.object-storage.integrity-audit.poll-delay-ms:60000}",
        initialDelayString = "${mamoji.object-storage.integrity-audit.poll-delay-ms:60000}"
    )
    public void auditWhenDue() {
        if (!enabled || !objectStorage.isEnabled()) {
            return;
        }
        try {
            scheduledJobs.runIfDue(JOB_NAME, cadenceMillis, leaseMillis, this::runAudit);
        } catch (RuntimeException ex) {
            failureCounter.increment();
            log.error("Receipt storage integrity audit failed", ex);
        }
    }

    ReceiptStorageAuditReport runAudit() {
        Instant scannedAt = Instant.now();
        String defaultBucket = objectStorage.bucketName();
        ReceiptStorageReferenceSnapshot referenceSnapshot = references.findAll(defaultBucket, maximumObjects);
        Set<String> buckets = new TreeSet<>();
        buckets.add(defaultBucket);
        referenceSnapshot.references().stream()
            .map(ReceiptObjectLocation::bucket)
            .forEach(buckets::add);
        List<ReceiptStoredObject> storedObjects = objectStorage.listObjects(
            buckets,
            RECEIPT_PREFIX,
            maximumObjects
        ).stream()
            .map(this::toStoredObject)
            .toList();
        ReceiptStorageAuditReport report = ReceiptStorageAuditReport.reconcile(
            referenceSnapshot.references(),
            storedObjects,
            referenceSnapshot.invalidReferenceCount(),
            scannedAt,
            orphanGracePeriod,
            sampleSize
        );
        successCounter.increment();
        orphanCounter.increment(report.orphanCandidateCount());
        missingCounter.increment(report.missingObjectCount());
        invalidReferenceCounter.increment(report.invalidReferenceCount());
        duplicateReferenceCounter.increment(report.duplicateReferenceCount());
        logReport(report);
        return report;
    }

    private ReceiptStoredObject toStoredObject(StoredObjectMetadata value) {
        return new ReceiptStoredObject(
            new ReceiptObjectLocation(value.bucket(), value.objectKey()),
            value.size(),
            value.lastModified()
        );
    }

    private void logReport(ReceiptStorageAuditReport report) {
        if (report.hasActionableFindings()) {
            log.warn(
                "Receipt storage audit found inconsistencies: references={}, distinctReferences={}, objects={}, bytes={}, "
                    + "orphanCandidates={}, youngUnreferenced={}, missing={}, invalidReferences={}, duplicateReferences={}, "
                    + "orphanSamples={}, missingSamples={}",
                report.validReferenceCount(), report.distinctReferenceCount(), report.storedObjectCount(),
                report.storedBytes(), report.orphanCandidateCount(), report.youngUnreferencedCount(),
                report.missingObjectCount(), report.invalidReferenceCount(), report.duplicateReferenceCount(),
                displayNames(report.orphanSamples()), displayNames(report.missingSamples())
            );
            return;
        }
        log.info(
            "Receipt storage audit completed: references={}, objects={}, bytes={}, youngUnreferenced={}",
            report.distinctReferenceCount(), report.storedObjectCount(), report.storedBytes(),
            report.youngUnreferencedCount()
        );
    }

    private List<String> displayNames(List<ReceiptObjectLocation> locations) {
        return locations.stream().map(ReceiptObjectLocation::displayName).toList();
    }

    private Counter findingCounter(MeterRegistry meterRegistry, String type) {
        return meterRegistry.counter("mamoji.receipt.storage.audit.findings", "type", type);
    }

    private long atLeastMinute(String name, long value) {
        if (value < 60_000) {
            throw new IllegalArgumentException(name + " must be at least 60000 milliseconds");
        }
        return value;
    }
}
