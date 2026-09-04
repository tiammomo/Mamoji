package com.mamoji.evidence.infrastructure;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** Runs the remaining evidence compatibility repair without loading demo fixtures. */
@Component
public class ReceiptVoucherDataInitializer {
    private final ReceiptVoucherRepository receiptVouchers;

    public ReceiptVoucherDataInitializer(ReceiptVoucherRepository receiptVouchers) {
        this.receiptVouchers = receiptVouchers;
    }

    @PostConstruct
    void initialize() {
        receiptVouchers.repairLegacyDefaults();
    }
}
