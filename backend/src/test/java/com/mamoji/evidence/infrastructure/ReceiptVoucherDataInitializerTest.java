package com.mamoji.evidence.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.ReceiptVoucher;
import com.mamoji.evidence.domain.ReceiptVoucherDraft;
import com.mamoji.platform.identity.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ReceiptVoucherDataInitializerTest {
    @Test
    void bootstrapModeOnlyRepairsExistingEvidenceData() {
        ReceiptVoucherRepository receiptVouchers = mock(ReceiptVoucherRepository.class);
        EnterpriseStore enterpriseStore = mock(EnterpriseStore.class);
        InMemoryStore coreStore = mock(InMemoryStore.class);
        ReceiptVoucherDataInitializer initializer = new ReceiptVoucherDataInitializer(
            receiptVouchers,
            enterpriseStore,
            coreStore,
            "bootstrap"
        );

        initializer.initialize();

        verify(receiptVouchers).repairLegacyDefaults();
        verify(receiptVouchers, never()).insert(any());
        verifyNoInteractions(enterpriseStore, coreStore);
    }

    @Test
    void demoModeCreatesEvidenceAndAuditFixturesOnce() {
        ReceiptVoucherRepository receiptVouchers = mock(ReceiptVoucherRepository.class);
        EnterpriseStore enterpriseStore = mock(EnterpriseStore.class);
        InMemoryStore coreStore = mock(InMemoryStore.class);
        Company company = new Company();
        company.id = 9;
        company.entityType = "company";
        User owner = new User();
        owner.id = 3;
        owner.role = 1;
        owner.nickname = "Owner";
        List<ReceiptVoucher> inserted = new ArrayList<>();
        AtomicLong ids = new AtomicLong(100);
        when(enterpriseStore.sortedCompanies()).thenReturn(List.of(company));
        when(enterpriseStore.sortedAuditLogs()).thenReturn(List.of());
        when(coreStore.sortedUsers()).thenReturn(List.of(owner));
        when(receiptVouchers.findByCompany(company.id)).thenReturn(List.of());
        when(receiptVouchers.insert(any())).thenAnswer(invocation -> {
            ReceiptVoucherDraft draft = invocation.getArgument(0);
            ReceiptVoucher voucher = new ReceiptVoucher();
            voucher.id = ids.incrementAndGet();
            voucher.companyId = draft.companyId();
            voucher.voucherNo = draft.voucherNo();
            voucher.title = draft.title();
            inserted.add(voucher);
            return voucher;
        });
        when(receiptVouchers.findAll()).thenAnswer(invocation -> List.copyOf(inserted));
        ReceiptVoucherDataInitializer initializer = new ReceiptVoucherDataInitializer(
            receiptVouchers,
            enterpriseStore,
            coreStore,
            "demo"
        );

        initializer.initialize();

        assertEquals(8, inserted.size());
        verify(receiptVouchers).repairLegacyDefaults();
        verify(receiptVouchers, times(8)).save(any());
        verify(enterpriseStore, times(8)).auditLog(
            anyLong(),
            anyString(),
            anyLong(),
            anyString(),
            anyString(),
            anyLong(),
            anyString()
        );
    }
}
