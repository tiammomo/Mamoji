package com.mamoji.evidence.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ReceiptStorageAuditReport(
    Instant scannedAt,
    long validReferenceCount,
    long distinctReferenceCount,
    long storedObjectCount,
    long storedBytes,
    long orphanCandidateCount,
    long youngUnreferencedCount,
    long missingObjectCount,
    long invalidReferenceCount,
    long duplicateReferenceCount,
    List<ReceiptObjectLocation> orphanSamples,
    List<ReceiptObjectLocation> missingSamples
) {
    private static final Comparator<ReceiptObjectLocation> LOCATION_ORDER = Comparator
        .comparing(ReceiptObjectLocation::bucket)
        .thenComparing(ReceiptObjectLocation::objectKey);

    public ReceiptStorageAuditReport {
        Objects.requireNonNull(scannedAt, "scannedAt");
        requireNonNegative(
            validReferenceCount,
            distinctReferenceCount,
            storedObjectCount,
            storedBytes,
            orphanCandidateCount,
            youngUnreferencedCount,
            missingObjectCount,
            invalidReferenceCount,
            duplicateReferenceCount
        );
        orphanSamples = List.copyOf(orphanSamples);
        missingSamples = List.copyOf(missingSamples);
    }

    public static ReceiptStorageAuditReport reconcile(
        Collection<ReceiptObjectLocation> references,
        Collection<ReceiptStoredObject> storedObjects,
        long invalidReferenceCount,
        Instant scannedAt,
        Duration orphanGracePeriod,
        int sampleSize
    ) {
        Objects.requireNonNull(references, "references");
        Objects.requireNonNull(storedObjects, "storedObjects");
        Objects.requireNonNull(scannedAt, "scannedAt");
        Objects.requireNonNull(orphanGracePeriod, "orphanGracePeriod");
        if (invalidReferenceCount < 0) {
            throw new IllegalArgumentException("Invalid receipt reference count must not be negative");
        }
        if (orphanGracePeriod.isNegative()) {
            throw new IllegalArgumentException("Receipt orphan grace period must not be negative");
        }
        if (sampleSize <= 0 || sampleSize > 100) {
            throw new IllegalArgumentException("Receipt storage audit sample size must be between 1 and 100");
        }

        Set<ReceiptObjectLocation> distinctReferences = new HashSet<>(references);
        Set<ReceiptObjectLocation> storedLocations = new HashSet<>();
        long storedBytes = 0L;
        for (ReceiptStoredObject storedObject : storedObjects) {
            if (!storedLocations.add(storedObject.location())) {
                throw new IllegalArgumentException("Receipt object inventory contains duplicate locations");
            }
            storedBytes = Math.addExact(storedBytes, storedObject.size());
        }

        Set<ReceiptObjectLocation> missing = new HashSet<>(distinctReferences);
        missing.removeAll(storedLocations);
        Instant orphanCutoff = scannedAt.minus(orphanGracePeriod);
        List<ReceiptStoredObject> unreferenced = storedObjects.stream()
            .filter(storedObject -> !distinctReferences.contains(storedObject.location()))
            .toList();
        List<ReceiptObjectLocation> orphanCandidates = unreferenced.stream()
            .filter(storedObject -> !storedObject.lastModified().isAfter(orphanCutoff))
            .map(ReceiptStoredObject::location)
            .toList();
        long youngUnreferencedCount = unreferenced.size() - orphanCandidates.size();
        long duplicateReferenceCount = references.size() - distinctReferences.size();

        return new ReceiptStorageAuditReport(
            scannedAt,
            references.size(),
            distinctReferences.size(),
            storedObjects.size(),
            storedBytes,
            orphanCandidates.size(),
            youngUnreferencedCount,
            missing.size(),
            invalidReferenceCount,
            duplicateReferenceCount,
            samples(orphanCandidates, sampleSize),
            samples(missing, sampleSize)
        );
    }

    public boolean hasActionableFindings() {
        return orphanCandidateCount > 0
            || missingObjectCount > 0
            || invalidReferenceCount > 0
            || duplicateReferenceCount > 0;
    }

    private static List<ReceiptObjectLocation> samples(
        Collection<ReceiptObjectLocation> locations,
        int sampleSize
    ) {
        return locations.stream().sorted(LOCATION_ORDER).limit(sampleSize).toList();
    }

    private static void requireNonNegative(long... values) {
        for (long value : values) {
            if (value < 0) {
                throw new IllegalArgumentException("Receipt storage audit counts must not be negative");
            }
        }
    }
}
