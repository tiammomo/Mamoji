package com.mamoji.evidence.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mamoji.evidence.domain.ReceiptStorageQuota;
import com.mamoji.evidence.domain.ReceiptStorageUsage;
import com.mamoji.service.support.ObjectStorageService;
import com.mamoji.service.support.ObjectStorageService.StoredObject;
import com.mamoji.service.support.ReceiptFileValidator.ValidatedReceiptFile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

class ReceiptStorageGuardTest {
    private static final ValidatedReceiptFile VALIDATED = new ValidatedReceiptFile("receipt.pdf", "application/pdf");
    private static final StoredObject STORED = new StoredObject(
        "minio", "mamoji", "receipts/company-7/receipt.pdf", null, "application/pdf"
    );

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void preservesMetadataOnlyModeWithoutCapacityQueries() {
        ReceiptStorageUsageRepository usage = mock(ReceiptStorageUsageRepository.class);
        ObjectStorageService objectStorage = mock(ObjectStorageService.class);
        MultipartFile file = file(128L);
        StoredObject metadata = new StoredObject(
            "metadata_only", null, "receipts/company-7/receipt.pdf", null, "application/pdf"
        );
        when(objectStorage.storeReceiptFile(7L, file, VALIDATED)).thenReturn(metadata);
        ReceiptStorageGuard guard = guard(usage, objectStorage, new SimpleMeterRegistry(), 1_000L, 80);

        assertSame(metadata, guard.store(7L, file, VALIDATED).storedObject());

        verifyNoInteractions(usage);
    }

    @Test
    void refusesDurableWritesOutsideASynchronizedTransaction() {
        ReceiptStorageUsageRepository usage = mock(ReceiptStorageUsageRepository.class);
        ObjectStorageService objectStorage = enabledObjectStorage();
        MultipartFile file = file(128L);
        ReceiptStorageGuard guard = guard(usage, objectStorage, new SimpleMeterRegistry(), 1_000L, 80);

        assertThrows(IllegalStateException.class, () -> guard.store(7L, file, VALIDATED));

        verifyNoInteractions(usage);
        verify(objectStorage, never()).storeReceiptFile(7L, file, VALIDATED);
    }

    @Test
    void rejectsAnUploadBeforeObjectStorageWhenTheCompanyQuotaIsExceeded() {
        ReceiptStorageUsageRepository usage = mock(ReceiptStorageUsageRepository.class);
        when(usage.findByCompany(7L)).thenReturn(new ReceiptStorageUsage(4L, 900L));
        ObjectStorageService objectStorage = enabledObjectStorage();
        MultipartFile file = file(101L);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ReceiptStorageGuard guard = guard(usage, objectStorage, meters, 1_000L, 80);
        beginTransactionSynchronization();

        assertThrows(ReceiptStorageQuota.CapacityExceededException.class, () -> guard.store(7L, file, VALIDATED));

        verify(usage).lockCompany(7L);
        verify(objectStorage, never()).storeReceiptFile(7L, file, VALIDATED);
        assertEquals(1.0, meters.get("mamoji.receipt.storage.capacity.rejections").counter().count());
    }

    @Test
    void keepsTheObjectAfterCommitAndEmitsTheCapacityWarning() {
        ReceiptStorageUsageRepository usage = mock(ReceiptStorageUsageRepository.class);
        when(usage.findByCompany(7L)).thenReturn(new ReceiptStorageUsage(3L, 700L));
        ObjectStorageService objectStorage = enabledObjectStorage();
        MultipartFile file = file(100L);
        when(objectStorage.storeReceiptFile(7L, file, VALIDATED)).thenReturn(STORED);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ReceiptStorageGuard guard = guard(usage, objectStorage, meters, 1_000L, 80);
        beginTransactionSynchronization();

        ReceiptStorageWrite storageWrite = guard.store(7L, file, VALIDATED);
        storageWrite.markReferenced();
        assertSame(STORED, storageWrite.storedObject());
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        verify(objectStorage, never()).deleteObject(STORED);
        assertEquals(1.0, meters.get("mamoji.receipt.storage.capacity.warnings").counter().count());
        assertEquals(0.0, compensationCount(meters, "success"));
    }

    @Test
    void removesAnUnreferencedObjectEvenWhenTheBatchTransactionCommits() {
        ReceiptStorageUsageRepository usage = mock(ReceiptStorageUsageRepository.class);
        when(usage.findByCompany(7L)).thenReturn(new ReceiptStorageUsage(1L, 100L));
        ObjectStorageService objectStorage = enabledObjectStorage();
        MultipartFile file = file(100L);
        when(objectStorage.storeReceiptFile(7L, file, VALIDATED)).thenReturn(STORED);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ReceiptStorageGuard guard = guard(usage, objectStorage, meters, 1_000L, 80);
        beginTransactionSynchronization();

        guard.store(7L, file, VALIDATED);
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        verify(objectStorage).deleteObject(STORED);
        assertEquals(1.0, compensationCount(meters, "success"));
    }

    @Test
    void removesTheObjectAfterRollback() {
        ReceiptStorageUsageRepository usage = mock(ReceiptStorageUsageRepository.class);
        when(usage.findByCompany(7L)).thenReturn(new ReceiptStorageUsage(1L, 100L));
        ObjectStorageService objectStorage = enabledObjectStorage();
        MultipartFile file = file(100L);
        when(objectStorage.storeReceiptFile(7L, file, VALIDATED)).thenReturn(STORED);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ReceiptStorageGuard guard = guard(usage, objectStorage, meters, 1_000L, 80);
        beginTransactionSynchronization();

        ReceiptStorageWrite storageWrite = guard.store(7L, file, VALIDATED);
        storageWrite.markReferenced();
        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(objectStorage).deleteObject(STORED);
        assertEquals(1.0, compensationCount(meters, "success"));
        assertEquals(0.0, compensationCount(meters, "failure"));
    }

    @Test
    void countsCompensationFailuresWithoutMaskingTransactionCompletion() {
        ReceiptStorageUsageRepository usage = mock(ReceiptStorageUsageRepository.class);
        when(usage.findByCompany(7L)).thenReturn(new ReceiptStorageUsage(1L, 100L));
        ObjectStorageService objectStorage = enabledObjectStorage();
        MultipartFile file = file(100L);
        when(objectStorage.storeReceiptFile(7L, file, VALIDATED)).thenReturn(STORED);
        doThrow(new IllegalStateException("MinIO unavailable")).when(objectStorage).deleteObject(STORED);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ReceiptStorageGuard guard = guard(usage, objectStorage, meters, 1_000L, 80);
        beginTransactionSynchronization();

        guard.store(7L, file, VALIDATED);
        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertEquals(0.0, compensationCount(meters, "success"));
        assertEquals(1.0, compensationCount(meters, "failure"));
    }

    private ReceiptStorageGuard guard(
        ReceiptStorageUsageRepository usage,
        ObjectStorageService objectStorage,
        SimpleMeterRegistry meters,
        long maximumBytes,
        int warningPercent
    ) {
        return new ReceiptStorageGuard(usage, objectStorage, meters, maximumBytes, warningPercent);
    }

    private ObjectStorageService enabledObjectStorage() {
        ObjectStorageService objectStorage = mock(ObjectStorageService.class);
        when(objectStorage.isEnabled()).thenReturn(true);
        return objectStorage;
    }

    private MultipartFile file(long size) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(size);
        return file;
    }

    private void beginTransactionSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void completeTransaction(int status) {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(status));
    }

    private double compensationCount(SimpleMeterRegistry meters, String outcome) {
        return meters.get("mamoji.receipt.storage.compensation")
            .tag("outcome", outcome)
            .counter()
            .count();
    }
}
