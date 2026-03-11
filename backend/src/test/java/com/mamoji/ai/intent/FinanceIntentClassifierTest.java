package com.mamoji.ai.intent;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test suite for FinanceIntentClassifierTest.
 */
class FinanceIntentClassifierTest {

    private final FinanceIntentClassifier classifier = new FinanceIntentClassifier();

    @Test
    void shouldDetectBudgetIntent() {
        FinanceIntentClassifier.FinanceIntent intent = classifier.classify("budget remaining and usage rate this month");
        Assertions.assertEquals(FinanceIntentClassifier.FinanceIntentType.BUDGET, intent.type());
    }

    @Test
    void shouldDetectCategoryIntent() {
        FinanceIntentClassifier.FinanceIntent intent = classifier.classify("category breakdown of expense and top category");
        Assertions.assertEquals(FinanceIntentClassifier.FinanceIntentType.CATEGORY, intent.type());
        Assertions.assertEquals(2, intent.transactionType());
    }

    @Test
    void shouldDetectTransactionIntent() {
        FinanceIntentClassifier.FinanceIntent intent = classifier.classify("show recent transaction record list");
        Assertions.assertEquals(FinanceIntentClassifier.FinanceIntentType.TRANSACTION, intent.type());
    }

    @Test
    void shouldDetectCashflowIntent() {
        FinanceIntentClassifier.FinanceIntent intent = classifier.classify("income expense cashflow and surplus this month");
        Assertions.assertEquals(FinanceIntentClassifier.FinanceIntentType.CASHFLOW, intent.type());
    }

    @Test
    void shouldDetectIncomeTransactionType() {
        FinanceIntentClassifier.FinanceIntent intent = classifier.classify("transaction list of recent income records");
        Assertions.assertEquals(FinanceIntentClassifier.FinanceIntentType.TRANSACTION, intent.type());
        Assertions.assertEquals(1, intent.transactionType());
    }

    @Test
    void shouldReturnUnknownForGenericQuestion() {
        FinanceIntentClassifier.FinanceIntent intent = classifier.classify("hello there");
        Assertions.assertEquals(FinanceIntentClassifier.FinanceIntentType.UNKNOWN, intent.type());
    }
}
