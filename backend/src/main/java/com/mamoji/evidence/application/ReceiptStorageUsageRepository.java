package com.mamoji.evidence.application;

import com.mamoji.evidence.domain.ReceiptStorageUsage;

public interface ReceiptStorageUsageRepository {
    void lockCompany(long companyId);

    ReceiptStorageUsage findByCompany(long companyId);
}
