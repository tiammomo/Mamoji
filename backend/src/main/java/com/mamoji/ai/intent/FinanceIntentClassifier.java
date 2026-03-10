package com.mamoji.ai.intent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class FinanceIntentClassifier {

    private static final List<String> BUDGET_HINTS = List.of(
        "budget", "limit", "quota", "remaining", "usage",
        "预算", "超支", "额度", "余量", "剩余", "执行率", "预算率"
    );
    private static final List<String> CATEGORY_HINTS = List.of(
        "category", "ratio", "top", "structure", "breakdown",
        "分类", "占比", "结构", "哪类", "最大", "大头", "分布"
    );
    private static final List<String> TRANSACTION_HINTS = List.of(
        "transaction", "record", "detail", "list", "ledger", "bill",
        "流水", "交易", "记录", "明细", "账单", "最近几笔", "最近"
    );
    private static final List<String> CASHFLOW_HINTS = List.of(
        "income", "expense", "cashflow", "saving", "balance", "surplus",
        "收入", "支出", "收支", "结余", "现金流", "节流", "省钱", "开销", "消费"
    );
    private static final List<String> INCOME_HINTS = List.of(
        "income", "salary", "bonus", "收入", "进账", "回款", "到账"
    );
    private static final List<String> EXPENSE_HINTS = List.of(
        "expense", "cost", "spend", "支出", "消费", "开销", "花费", "花了"
    );
    private static final List<String> PERIOD_HINTS = List.of(
        "本月", "本年", "今年", "上月", "上年", "近7天", "近30天", "同比", "环比", "month", "year"
    );

    public FinanceIntent classify(String message) {
        String text = message == null ? "" : message.trim();
        if (text.isBlank()) {
            return new FinanceIntent(FinanceIntentType.UNKNOWN, 0.0D, null);
        }

        String lower = text.toLowerCase(Locale.ROOT);
        int budgetScore = score(lower, BUDGET_HINTS);
        int categoryScore = score(lower, CATEGORY_HINTS);
        int transactionScore = score(lower, TRANSACTION_HINTS);
        int cashflowScore = score(lower, CASHFLOW_HINTS);

        if (containsAny(lower, "预算执行率", "预算使用率", "预算剩余", "超支风险", "remaining budget")) {
            budgetScore += 2;
        }
        if (containsAny(lower, "按分类", "分类占比", "top分类", "最大分类", "category breakdown")) {
            categoryScore += 2;
        }
        if (containsAny(lower, "最近", "明细", "流水列表", "最近几笔", "transaction list")) {
            transactionScore += 2;
        }
        if (containsAny(lower, "本月收入", "本月支出", "本月结余", "收支情况", "cash flow")) {
            cashflowScore += 2;
        }
        if (containsAny(lower, PERIOD_HINTS) && (containsAny(lower, INCOME_HINTS) || containsAny(lower, EXPENSE_HINTS))) {
            cashflowScore += 1;
        }

        Score winner = chooseWinner(budgetScore, categoryScore, transactionScore, cashflowScore);
        Integer txType = resolveTransactionType(lower);
        double confidence = confidence(winner.score, budgetScore + categoryScore + transactionScore + cashflowScore);
        return new FinanceIntent(winner.type, confidence, txType);
    }

    private int score(String text, List<String> hints) {
        int score = 0;
        for (String hint : hints) {
            if (text.contains(hint.toLowerCase(Locale.ROOT))) {
                score += 1;
            }
        }
        return score;
    }

    private Integer resolveTransactionType(String lower) {
        boolean hasIncome = containsAny(lower, INCOME_HINTS);
        boolean hasExpense = containsAny(lower, EXPENSE_HINTS);
        if (hasIncome && !hasExpense) {
            return 1;
        }
        if (hasExpense && !hasIncome) {
            return 2;
        }
        return null;
    }

    private Score chooseWinner(int budget, int category, int transaction, int cashflow) {
        FinanceIntentType type = FinanceIntentType.UNKNOWN;
        int max = 0;

        if (budget > max) {
            max = budget;
            type = FinanceIntentType.BUDGET;
        }
        if (category > max) {
            max = category;
            type = FinanceIntentType.CATEGORY;
        }
        if (transaction > max) {
            max = transaction;
            type = FinanceIntentType.TRANSACTION;
        }
        if (cashflow > max) {
            max = cashflow;
            type = FinanceIntentType.CASHFLOW;
        }
        if (max <= 0) {
            return new Score(FinanceIntentType.UNKNOWN, 0);
        }
        return new Score(type, max);
    }

    private double confidence(int max, int total) {
        if (max <= 0 || total <= 0) {
            return 0.0D;
        }
        return Math.min(1.0D, Math.max(0.0D, (double) max / total));
    }

    private boolean containsAny(String text, List<String> candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, String... candidates) {
        return containsAny(text, List.of(candidates));
    }

    private record Score(FinanceIntentType type, int score) {
    }

    public record FinanceIntent(FinanceIntentType type, double confidence, Integer transactionType) {
    }

    public enum FinanceIntentType {
        BUDGET,
        CATEGORY,
        TRANSACTION,
        CASHFLOW,
        UNKNOWN
    }
}
