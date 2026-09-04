package com.mamoji.platform.tenant;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EntityTransferPolicyTest {
    @Test
    void rejectsSameSubjectTransfers() {
        EntityTransfer transfer = validTransfer();
        transfer.toEntityId = transfer.fromEntityId;

        assertThrows(IllegalArgumentException.class, () -> EntityTransferPolicy.normalizeAndValidate(transfer));
    }

    @Test
    void rejectsUnsupportedTypesAndExcessFractionalPrecision() {
        EntityTransfer unsupported = validTransfer();
        unsupported.transferType = "cash_move";
        assertThrows(IllegalArgumentException.class, () -> EntityTransferPolicy.normalizeAndValidate(unsupported));

        EntityTransfer imprecise = validTransfer();
        imprecise.amount = new BigDecimal("1.00001");
        assertThrows(IllegalArgumentException.class, () -> EntityTransferPolicy.normalizeAndValidate(imprecise));
    }

    private EntityTransfer validTransfer() {
        EntityTransfer transfer = new EntityTransfer();
        transfer.fromEntityId = 3;
        transfer.toEntityId = 7;
        transfer.transferType = "inter_entity_transfer";
        transfer.amount = BigDecimal.ONE;
        transfer.currency = "CNY";
        transfer.transferDate = "2026-09-04";
        transfer.status = "recorded";
        transfer.operatorUserId = 9;
        return transfer;
    }
}
