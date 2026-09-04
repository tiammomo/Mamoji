package com.mamoji.evidence.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mamoji.evidence.domain.ReceiptStorageQuota;
import com.mamoji.operations.application.TransactionLinkQuery;
import com.mamoji.platform.audit.application.AuditTrailService;
import com.mamoji.platform.identity.User;
import com.mamoji.platform.tenant.Company;
import com.mamoji.service.OutboxEventService;
import com.mamoji.service.support.AccessControlService;
import com.mamoji.service.support.ObjectStorageService;
import com.mamoji.service.support.ReceiptFileValidator.ValidatedReceiptFile;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class ReceiptApplicationServiceTest {
    @Test
    void mapsCompanyStorageQuotaRejectionToHttp507BeforeCreatingVoucher() {
        AccessControlService accessControl = mock(AccessControlService.class);
        AuditTrailService auditTrail = mock(AuditTrailService.class);
        ReceiptVoucherRepository receiptVouchers = mock(ReceiptVoucherRepository.class);
        ReceiptFileHashRepository receiptFileHashes = mock(ReceiptFileHashRepository.class);
        TransactionLinkQuery transactionLinks = mock(TransactionLinkQuery.class);
        ObjectStorageService objectStorage = mock(ObjectStorageService.class);
        ReceiptStorageGuard storageGuard = mock(ReceiptStorageGuard.class);
        OutboxEventService outbox = mock(OutboxEventService.class);
        ReceiptApplicationService service = new ReceiptApplicationService(
            accessControl,
            auditTrail,
            receiptVouchers,
            receiptFileHashes,
            transactionLinks,
            objectStorage,
            storageGuard,
            outbox
        );
        User user = new User();
        user.id = 9L;
        Company company = new Company();
        company.id = 7L;
        MockMultipartFile file = new MockMultipartFile(
            "file", "receipt.pdf", "application/pdf", "%PDF-receipt".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        ValidatedReceiptFile validated = new ValidatedReceiptFile("receipt.pdf", "application/pdf");
        ReceiptUploadCommand command = new ReceiptUploadCommand(
            7L, null, null, null, "reimbursement", null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null
        );
        when(accessControl.requireUser("Bearer token")).thenReturn(user);
        when(accessControl.resolveCompany(user, 7L)).thenReturn(company);
        when(objectStorage.validateReceiptFile(file)).thenReturn(validated);
        when(receiptFileHashes.findVoucherId(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any()))
            .thenReturn(OptionalLong.empty());
        when(storageGuard.store(7L, file, validated)).thenThrow(
            new ReceiptStorageQuota.CapacityExceededException(900L, file.getSize(), 1_000L)
        );

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.upload("Bearer token", file, command)
        );

        assertEquals(HttpStatus.INSUFFICIENT_STORAGE, exception.getStatusCode());
        assertEquals("Company receipt storage quota exceeded", exception.getReason());
        verifyNoInteractions(receiptVouchers);
    }
}
