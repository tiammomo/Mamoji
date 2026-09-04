package com.mamoji.evidence.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mamoji.platform.tenant.Company;
import com.mamoji.domain.Models.ReceiptVoucher;
import com.mamoji.evidence.domain.ReceiptVoucherDraft;
import com.mamoji.platform.identity.account.application.UserDirectory;
import com.mamoji.platform.tenant.CompanyRepository;
import com.mamoji.platform.audit.application.AuditTrailService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ReceiptVoucherDataInitializerTest {
    @Test
    void bootstrapModeOnlyRepairsExistingEvidenceData() {
        ReceiptVoucherRepository receiptVouchers = mock(ReceiptVoucherRepository.class);
        ReceiptVoucherDataInitializer initializer = new ReceiptVoucherDataInitializer(receiptVouchers);

        initializer.initialize();

        verify(receiptVouchers).repairLegacyDefaults();
        verify(receiptVouchers, never()).insert(any());
    }

    @Test
    void demoModeCreatesEvidenceAndAuditFixturesOnce() {
        ReceiptVoucherRepository receiptVouchers = mock(ReceiptVoucherRepository.class);
        CompanyRepository companies = mock(CompanyRepository.class);
        AuditTrailService auditTrail = mock(AuditTrailService.class);
        UserDirectory userDirectory = mock(UserDirectory.class);
        Company company = new Company();
        company.id = 9;
        company.entityType = "company";
        UserDirectory.Entry owner = new UserDirectory.Entry(
            3, "owner@mamoji.test", "Owner", "", null, 1, 15
        );
        List<ReceiptVoucher> inserted = new ArrayList<>();
        AtomicLong ids = new AtomicLong(100);
        when(companies.findAll()).thenReturn(List.of(company));
        when(auditTrail.existsByEntityType("receipt_voucher")).thenReturn(false);
        when(userDirectory.findAll()).thenReturn(List.of(owner));
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
        DemoReceiptVoucherDataInitializer initializer = new DemoReceiptVoucherDataInitializer(
            receiptVouchers,
            companies,
            auditTrail,
            userDirectory
        );

        initializer.initialize();

        assertEquals(8, inserted.size());
        verify(receiptVouchers, never()).repairLegacyDefaults();
        verify(receiptVouchers, times(8)).save(any());
        verify(auditTrail, times(8)).record(
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
