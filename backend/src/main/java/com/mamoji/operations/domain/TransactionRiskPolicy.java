package com.mamoji.operations.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure risk projection for a transaction and its company-scoped history. */
public final class TransactionRiskPolicy {
    private TransactionRiskPolicy() {
    }

    public static TransactionRiskAssessment assess(
        TransactionRecord transaction,
        List<TransactionRecord> history
    ) {
        YearMonth month = YearMonth.from(LocalDate.parse(transaction.date));
        YearMonth previousMonth = month.minusMonths(1);
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal categoryCurrent = BigDecimal.ZERO;
        BigDecimal categoryLast = BigDecimal.ZERO;
        long dailyExpenseCount = 0;
        long duplicateCount = 0;
        List<TransactionRecord> transactions = new ArrayList<>(history);
        transactions.removeIf(item -> item.id == transaction.id);
        transactions.add(transaction);
        for (TransactionRecord item : transactions) {
            if (item.userId != transaction.userId || !Objects.equals(item.companyId, transaction.companyId)) {
                continue;
            }
            boolean currentMonth = sameMonth(item.date, month);
            if (currentMonth && item.type == 1) {
                income = income.add(item.amount);
            }
            if (currentMonth && item.type == 2) {
                expense = expense.add(item.amount);
            } else if (currentMonth && item.type == 3) {
                expense = expense.subtract(item.amount);
            }
            if (item.type == 2 && item.date.equals(transaction.date)) {
                dailyExpenseCount++;
            }
            if (item.id != transaction.id
                && item.type == transaction.type
                && item.categoryId == transaction.categoryId
                && item.accountId == transaction.accountId
                && item.amount.compareTo(transaction.amount) == 0
                && item.date.equals(transaction.date)) {
                duplicateCount++;
            }
            if (item.categoryId == transaction.categoryId && item.type == 2 && currentMonth) {
                categoryCurrent = categoryCurrent.add(item.amount);
            } else if (item.categoryId == transaction.categoryId && item.type == 3 && currentMonth) {
                categoryCurrent = categoryCurrent.subtract(item.amount);
            } else if (item.categoryId == transaction.categoryId && item.type == 2 && sameMonth(item.date, previousMonth)) {
                categoryLast = categoryLast.add(item.amount);
            } else if (item.categoryId == transaction.categoryId && item.type == 3 && sameMonth(item.date, previousMonth)) {
                categoryLast = categoryLast.subtract(item.amount);
            }
        }
        expense = expense.max(BigDecimal.ZERO);
        categoryCurrent = categoryCurrent.max(BigDecimal.ZERO);
        categoryLast = categoryLast.max(BigDecimal.ZERO);
        List<String> flags = new ArrayList<>();
        String level = "low";
        if (transaction.amount.compareTo(new BigDecimal("5000")) >= 0 && transaction.type == 2) {
            flags.add("large_transaction");
            level = "high";
        }
        if (duplicateCount > 0) {
            flags.add("duplicate_candidate");
            level = level.equals("high") ? "high" : "medium";
        }
        double ratio = income.compareTo(BigDecimal.ZERO) == 0
            ? 0
            : expense.divide(income, 4, RoundingMode.HALF_UP).doubleValue();
        if (ratio > 0.8) {
            flags.add("expense_income_ratio_high");
            level = "high";
        }
        String message = flags.isEmpty() ? "交易风险较低" : "交易触发了风控提示";
        return new TransactionRiskAssessment(
            level,
            flags,
            message,
            income,
            expense,
            ratio,
            dailyExpenseCount,
            duplicateCount,
            categoryCurrent,
            categoryLast
        );
    }

    private static boolean sameMonth(String date, YearMonth month) {
        return YearMonth.from(LocalDate.parse(date)).equals(month);
    }
}
