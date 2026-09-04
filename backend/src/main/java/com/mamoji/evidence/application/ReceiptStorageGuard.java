package com.mamoji.evidence.application;

import com.mamoji.evidence.domain.ReceiptStorageQuota;
import com.mamoji.evidence.domain.ReceiptStorageUsage;
import com.mamoji.service.support.ObjectStorageService;
import com.mamoji.service.support.ObjectStorageService.StoredObject;
import com.mamoji.service.support.ReceiptFileValidator.ValidatedReceiptFile;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/** Coordinates durable object writes with company capacity and database commit. */
@Service
public class ReceiptStorageGuard {
    private static final Logger log = LoggerFactory.getLogger(ReceiptStorageGuard.class);

    private final ReceiptStorageUsageRepository storageUsage;
    private final ObjectStorageService objectStorage;
    private final ReceiptStorageQuota quota;
    private final Counter capacityWarningCounter;
    private final Counter capacityRejectionCounter;
    private final Counter compensationSuccessCounter;
    private final Counter compensationFailureCounter;

    public ReceiptStorageGuard(
        ReceiptStorageUsageRepository storageUsage,
        ObjectStorageService objectStorage,
        MeterRegistry meterRegistry,
        @Value("${mamoji.object-storage.max-bytes-per-company:10737418240}") long maximumBytes,
        @Value("${mamoji.object-storage.warning-percent:80}") int warningPercent
    ) {
        this.storageUsage = storageUsage;
        this.objectStorage = objectStorage;
        this.quota = new ReceiptStorageQuota(maximumBytes, warningPercent);
        this.capacityWarningCounter = meterRegistry.counter("mamoji.receipt.storage.capacity.warnings");
        this.capacityRejectionCounter = meterRegistry.counter("mamoji.receipt.storage.capacity.rejections");
        this.compensationSuccessCounter = meterRegistry.counter(
            "mamoji.receipt.storage.compensation", "outcome", "success"
        );
        this.compensationFailureCounter = meterRegistry.counter(
            "mamoji.receipt.storage.compensation", "outcome", "failure"
        );
    }

    public ReceiptStorageWrite store(
        long companyId,
        MultipartFile file,
        ValidatedReceiptFile validatedFile
    ) {
        if (!objectStorage.isEnabled()) {
            return new ReceiptStorageWrite(objectStorage.storeReceiptFile(companyId, file, validatedFile));
        }
        requireTransactionalUpload();
        storageUsage.lockCompany(companyId);
        ReceiptStorageUsage usage = storageUsage.findByCompany(companyId);
        long projectedBytes;
        try {
            projectedBytes = quota.reserve(usage.usedBytes(), file.getSize());
        } catch (ReceiptStorageQuota.CapacityExceededException ex) {
            capacityRejectionCounter.increment();
            log.warn(
                "Receipt storage quota rejected upload: companyId={}, objectCount={}, usedBytes={}, requestedBytes={}, maximumBytes={}",
                companyId,
                usage.objectCount(),
                ex.usedBytes(),
                ex.requestedBytes(),
                ex.maximumBytes()
            );
            throw ex;
        }
        if (quota.warningReached(projectedBytes)) {
            capacityWarningCounter.increment();
            log.warn(
                "Receipt storage capacity warning: companyId={}, objectCount={}, projectedBytes={}, maximumBytes={}, warningPercent={}",
                companyId,
                usage.objectCount() + 1,
                projectedBytes,
                quota.maximumBytes(),
                quota.warningPercent()
            );
        }

        StoredObject storedObject = objectStorage.storeReceiptFile(companyId, file, validatedFile);
        ReceiptStorageWrite storageWrite = new ReceiptStorageWrite(storedObject);
        try {
            registerCompensation(companyId, storageWrite);
        } catch (RuntimeException ex) {
            compensateUnreferencedObject(companyId, storedObject);
            throw ex;
        }
        return storageWrite;
    }

    private void requireTransactionalUpload() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Durable receipt upload requires an active synchronized transaction");
        }
    }

    private void registerCompensation(long companyId, ReceiptStorageWrite storageWrite) {
        StoredObject storedObject = storageWrite.storedObject();
        if (!"minio".equals(storedObject.provider())) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED && storageWrite.referenced()) {
                    return;
                }
                compensateUnreferencedObject(companyId, storedObject);
            }
        });
    }

    private void compensateUnreferencedObject(long companyId, StoredObject storedObject) {
        try {
            objectStorage.deleteObject(storedObject);
            compensationSuccessCounter.increment();
            log.info(
                "Compensated unreferenced receipt object: companyId={}, bucket={}, objectKey={}",
                companyId,
                storedObject.bucket(),
                storedObject.objectKey()
            );
        } catch (RuntimeException ex) {
            compensationFailureCounter.increment();
            log.error(
                "Failed to compensate unreferenced receipt object: companyId={}, bucket={}, objectKey={}",
                companyId,
                storedObject.bucket(),
                storedObject.objectKey(),
                ex
            );
        }
    }
}
