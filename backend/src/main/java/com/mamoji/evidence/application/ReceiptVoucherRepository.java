package com.mamoji.evidence.application;

import com.mamoji.common.PagedResponse;
import com.mamoji.evidence.domain.ReceiptSummary;
import com.mamoji.evidence.domain.ReceiptVoucher;
import com.mamoji.evidence.domain.ReceiptVoucherDraft;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for receipt vouchers owned by the Evidence module. */
public interface ReceiptVoucherRepository {
    PagedResponse<ReceiptVoucher> findPage(long companyId, ReceiptListQuery query);

    ReceiptSummary summarize(long companyId);

    List<ReceiptVoucher> findByCompany(long companyId);

    List<ReceiptVoucher> findAll();

    Optional<ReceiptVoucher> findById(long id);

    Optional<ReceiptVoucher> findByIdForUpdate(long id);

    long count();

    ReceiptVoucher insert(ReceiptVoucherDraft draft);

    void save(ReceiptVoucher voucher);
}
