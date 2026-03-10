package com.mamoji.ai.intent;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FinanceIntentClassifierTest {

    private final FinanceIntentClassifier classifier = new FinanceIntentClassifier();

    @Test
    void shouldDetectBudgetIntent() {
        FinanceIntentClassifier.FinanceIntent intent = classifier.classify("我这个月预算执行率怎么样，是否有超支风险？");
        Assertions.assertEquals(FinanceIntentClassifier.FinanceIntentType.BUDGET, intent.type());
    }

    @Test
    void shouldDetectCategoryIntent() {
        FinanceIntentClassifier.FinanceIntent intent = classifier.classify("帮我看下本月支出分类占比和top分类");
        Assertions.assertEquals(FinanceIntentClassifier.FinanceIntentType.CATEGORY, intent.type());
        Assertions.assertEquals(2, intent.transactionType());
    }

    @Test
    void shouldDetectTransactionIntent() {
        FinanceIntentClassifier.FinanceIntent intent = classifier.classify("列出最近几笔交易流水明细给我看看");
        Assertions.assertEquals(FinanceIntentClassifier.FinanceIntentType.TRANSACTION, intent.type());
    }

    @Test
    void shouldDetectCashflowIntent() {
        FinanceIntentClassifier.FinanceIntent intent = classifier.classify("本月收入、支出和结余分别是多少？");
        Assertions.assertEquals(FinanceIntentClassifier.FinanceIntentType.CASHFLOW, intent.type());
    }

    @Test
    void shouldDetectIncomeTransactionType() {
        FinanceIntentClassifier.FinanceIntent intent = classifier.classify("最近几笔收入明细");
        Assertions.assertEquals(FinanceIntentClassifier.FinanceIntentType.TRANSACTION, intent.type());
        Assertions.assertEquals(1, intent.transactionType());
    }

    @Test
    void shouldReturnUnknownForGenericQuestion() {
        FinanceIntentClassifier.FinanceIntent intent = classifier.classify("你好呀");
        Assertions.assertEquals(FinanceIntentClassifier.FinanceIntentType.UNKNOWN, intent.type());
    }
}
