package com.mamoji.operations.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionRiskPolicyTest {
    @Test
    void includesCurrentTransactionAndIgnoresOtherCompanies() {
        TransactionRecord income = transaction(1, 7, 11, 1, "100", 2, 3, "2026-08-10");
        TransactionRecord expense = transaction(2, 7, 11, 2, "90", 4, 3, "2026-08-11");
        TransactionRecord otherCompany = transaction(3, 7, 99, 2, "900", 4, 3, "2026-08-11");

        TransactionRiskAssessment risk = TransactionRiskPolicy.assess(expense, List.of(income, otherCompany));

        assertEquals(new BigDecimal("100"), risk.monthlyIncome());
        assertEquals(new BigDecimal("90"), risk.monthlyExpense());
        assertEquals("high", risk.level());
        assertTrue(risk.flags().contains("expense_income_ratio_high"));
    }

    @Test
    void marksSameBusinessKeyAsDuplicateCandidate() {
        TransactionRecord existing = transaction(1, 7, 11, 2, "88.00", 4, 3, "2026-08-11");
        TransactionRecord candidate = transaction(2, 7, 11, 2, "88.00", 4, 3, "2026-08-11");

        TransactionRiskAssessment risk = TransactionRiskPolicy.assess(candidate, List.of(existing));

        assertEquals(1L, risk.duplicateCount());
        assertEquals("medium", risk.level());
        assertTrue(risk.flags().contains("duplicate_candidate"));
    }

    private TransactionRecord transaction(
        long id,
        long userId,
        long companyId,
        int type,
        String amount,
        long categoryId,
        long accountId,
        String date
    ) {
        TransactionRecord transaction = new TransactionRecord();
        transaction.id = id;
        transaction.userId = userId;
        transaction.companyId = companyId;
        transaction.type = type;
        transaction.amount = new BigDecimal(amount);
        transaction.categoryId = categoryId;
        transaction.accountId = accountId;
        transaction.date = date;
        return transaction;
    }
}
