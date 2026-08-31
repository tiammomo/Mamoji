package com.mamoji.evidence.infrastructure;

import com.mamoji.domain.Models.ReceiptVoucher;
import com.mamoji.evidence.domain.ReceiptVoucherDraft;
import com.mamoji.repository.EnterpriseStore;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Evidence-owned persistence boundary for receipt vouchers.
 *
 * <p>The current adapter delegates to the compatibility store while its JDBC
 * implementation is moved incrementally. Business services must depend on this
 * boundary instead of the compatibility store.</p>
 */
@Repository
public class ReceiptVoucherRepository {
    private final EnterpriseStore compatibilityStore;

    public ReceiptVoucherRepository(EnterpriseStore compatibilityStore) {
        this.compatibilityStore = compatibilityStore;
    }

    public List<ReceiptVoucher> findByCompany(long companyId) {
        return compatibilityStore.sortedReceiptVouchers(companyId);
    }

    public List<ReceiptVoucher> findAll() {
        return compatibilityStore.allReceiptVouchers();
    }

    public Optional<ReceiptVoucher> findById(long id) {
        return compatibilityStore.findReceiptVoucher(id);
    }

    public Optional<ReceiptVoucher> findByIdForUpdate(long id) {
        return compatibilityStore.findReceiptVoucherForUpdate(id);
    }

    public long count() {
        return compatibilityStore.countReceiptVouchers();
    }

    public ReceiptVoucher insert(ReceiptVoucherDraft draft) {
        return compatibilityStore.receiptVoucher(
            draft.companyId(),
            draft.transactionId(),
            draft.voucherNo(),
            draft.title(),
            draft.voucherType(),
            draft.direction(),
            draft.counterparty(),
            draft.amount().toPlainString(),
            draft.taxAmount().toPlainString(),
            draft.issueDate(),
            draft.dueDate(),
            draft.status(),
            draft.fileName(),
            draft.fileSize(),
            draft.fileType(),
            draft.riskLevel(),
            draft.note(),
            draft.operatorUserId()
        );
    }

    public void save(ReceiptVoucher voucher) {
        compatibilityStore.saveReceiptVoucher(voucher);
    }
}
