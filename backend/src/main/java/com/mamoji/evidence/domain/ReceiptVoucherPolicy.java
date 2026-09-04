package com.mamoji.evidence.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/** Domain defaults and derived accounting data for receipt vouchers. */
public final class ReceiptVoucherPolicy {
    private static final BigDecimal APPROVAL_THRESHOLD = new BigDecimal("5000");

    private ReceiptVoucherPolicy() {
    }

    public static ReceiptVoucher initialize(ReceiptVoucherDraft draft, LocalDate today, String timestamp) {
        ReceiptVoucher voucher = new ReceiptVoucher();
        voucher.companyId = draft.companyId();
        voucher.transactionId = draft.transactionId();
        voucher.voucherNo = draft.voucherNo();
        voucher.title = draft.title();
        voucher.voucherType = draft.voucherType();
        voucher.direction = draft.direction();
        voucher.counterparty = draft.counterparty();
        voucher.amount = money(draft.amount());
        voucher.taxAmount = money(draft.taxAmount());
        voucher.issueDate = isBlank(draft.issueDate()) ? today.toString() : draft.issueDate();
        voucher.dueDate = isBlank(draft.dueDate()) ? null : draft.dueDate();
        voucher.status = isBlank(draft.status()) ? "pending_review" : draft.status();
        voucher.taxRate = inferredTaxRate(voucher);
        voucher.taxPeriod = taxPeriodFor(voucher.issueDate, today);
        voucher.invoiceCheckStatus = defaultInvoiceCheckStatus(voucher.voucherType);
        voucher.deductionStatus = defaultDeductionStatus(voucher.voucherType);
        voucher.reimbursementStatus = defaultReimbursementStatus(voucher.voucherType);
        voucher.approvalStatus = defaultApprovalStatus(voucher);
        voucher.accountingStatus = defaultAccountingStatus(voucher);
        voucher.accountingEntry = accountingEntryFor(voucher);
        if ("posted".equals(voucher.accountingStatus)) {
            voucher.accountedAt = timestamp;
        }
        voucher.fileName = draft.fileName();
        voucher.fileSize = draft.fileSize();
        voucher.fileType = draft.fileType();
        voucher.fileStorageProvider = isBlank(voucher.fileName) ? "none" : "metadata_only";
        voucher.riskLevel = isBlank(draft.riskLevel()) ? "low" : draft.riskLevel();
        voucher.note = draft.note();
        voucher.operatorUserId = draft.operatorUserId();
        voucher.createdAt = timestamp;
        voucher.updatedAt = timestamp;
        return voucher;
    }

    public static boolean hydrate(ReceiptVoucher voucher, LocalDate today) {
        boolean updated = false;
        if (voucher.amount == null) {
            voucher.amount = BigDecimal.ZERO;
            updated = true;
        }
        if (voucher.taxAmount == null) {
            voucher.taxAmount = BigDecimal.ZERO;
            updated = true;
        }
        if (voucher.taxRate == null) {
            voucher.taxRate = inferredTaxRate(voucher);
            updated = true;
        }
        if (isBlank(voucher.taxPeriod)) {
            voucher.taxPeriod = taxPeriodFor(voucher.issueDate, today);
            updated = true;
        }
        if (isBlank(voucher.invoiceCheckStatus)
            || ("not_required".equals(voucher.invoiceCheckStatus) && isInvoiceVoucher(voucher.voucherType))) {
            voucher.invoiceCheckStatus = isClosedVoucher(voucher.status)
                ? "verified"
                : defaultInvoiceCheckStatus(voucher.voucherType);
            updated = true;
        }
        if (isBlank(voucher.deductionStatus)
            || ("not_applicable".equals(voucher.deductionStatus) && "purchase_invoice".equals(voucher.voucherType))) {
            voucher.deductionStatus = isClosedVoucher(voucher.status)
                ? "deductible"
                : defaultDeductionStatus(voucher.voucherType);
            updated = true;
        }
        if (isBlank(voucher.reimbursementStatus)
            || ("not_applicable".equals(voucher.reimbursementStatus) && "reimbursement".equals(voucher.voucherType))) {
            voucher.reimbursementStatus = "archived".equals(voucher.status)
                ? "archived"
                : defaultReimbursementStatus(voucher.voucherType);
            updated = true;
        }
        if (isBlank(voucher.approvalStatus)
            || ("not_required".equals(voucher.approvalStatus) && requiresApproval(voucher))) {
            voucher.approvalStatus = defaultApprovalStatus(voucher);
            updated = true;
        }
        if (isBlank(voucher.accountingStatus)) {
            voucher.accountingStatus = defaultAccountingStatus(voucher);
            updated = true;
        }
        if (isBlank(voucher.accountingEntry)) {
            voucher.accountingEntry = accountingEntryFor(voucher);
            updated = true;
        }
        if (isBlank(voucher.accountingVoucherNo)
            && List.of("draft", "posted").contains(voucher.accountingStatus)
            && voucher.id > 0) {
            voucher.accountingVoucherNo = accountingVoucherNoFor(voucher, today);
            updated = true;
        }
        if ("posted".equals(voucher.accountingStatus) && isBlank(voucher.accountedAt)) {
            voucher.accountedAt = voucher.updatedAt == null ? voucher.createdAt : voucher.updatedAt;
            updated = true;
        }
        if (isBlank(voucher.fileStorageProvider)) {
            voucher.fileStorageProvider = isBlank(voucher.fileName) ? "none" : "metadata_only";
            updated = true;
        }
        return updated;
    }

    private static BigDecimal inferredTaxRate(ReceiptVoucher voucher) {
        BigDecimal taxAmount = money(voucher.taxAmount);
        BigDecimal taxExcludedAmount = money(voucher.amount).subtract(taxAmount);
        if (taxExcludedAmount.signum() <= 0 || taxAmount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return taxAmount.multiply(new BigDecimal("100")).divide(taxExcludedAmount, 2, RoundingMode.HALF_UP);
    }

    private static String taxPeriodFor(String issueDate, LocalDate today) {
        return issueDate == null || issueDate.length() < 7
            ? today.toString().substring(0, 7)
            : issueDate.substring(0, 7);
    }

    private static String defaultInvoiceCheckStatus(String voucherType) {
        return switch (voucherType == null ? "" : voucherType) {
            case "sales_invoice", "purchase_invoice" -> "pending";
            default -> "not_required";
        };
    }

    private static boolean isInvoiceVoucher(String voucherType) {
        return "sales_invoice".equals(voucherType) || "purchase_invoice".equals(voucherType);
    }

    private static boolean isClosedVoucher(String status) {
        return "verified".equals(status) || "linked".equals(status) || "archived".equals(status);
    }

    private static String defaultDeductionStatus(String voucherType) {
        return "purchase_invoice".equals(voucherType) ? "pending" : "not_applicable";
    }

    private static String defaultReimbursementStatus(String voucherType) {
        return "reimbursement".equals(voucherType) ? "submitted" : "not_applicable";
    }

    private static boolean requiresApproval(ReceiptVoucher voucher) {
        return "reimbursement".equals(voucher.voucherType)
            || "contract".equals(voucher.voucherType)
            || money(voucher.amount).compareTo(APPROVAL_THRESHOLD) >= 0;
    }

    private static String defaultApprovalStatus(ReceiptVoucher voucher) {
        return requiresApproval(voucher) ? "not_submitted" : "not_required";
    }

    private static String defaultAccountingStatus(ReceiptVoucher voucher) {
        if ("rejected".equals(voucher.status)) {
            return "not_started";
        }
        if ("archived".equals(voucher.status) || "linked".equals(voucher.status)) {
            return "posted";
        }
        if ("verified".equals(voucher.status) || "approved".equals(voucher.approvalStatus)) {
            return "draft";
        }
        return "not_started";
    }

    private static String accountingVoucherNoFor(ReceiptVoucher voucher, LocalDate today) {
        String period = taxPeriodFor(voucher.issueDate, today).replace("-", "");
        return "JV-" + period + "-" + String.format("%04d", Math.max(1, voucher.id));
    }

    private static String accountingEntryFor(ReceiptVoucher voucher) {
        BigDecimal amount = money(voucher.amount);
        BigDecimal tax = money(voucher.taxAmount);
        BigDecimal netAmount = amount.subtract(tax).max(BigDecimal.ZERO);
        if ("income".equals(voucher.direction)) {
            String debit = voucher.transactionId == null ? "应收账款" : "银行存款";
            return "借：" + debit + " " + moneyText(amount) + "；贷：主营业务收入 " + moneyText(netAmount)
                + (tax.signum() > 0 ? "，应交税费-销项税额 " + moneyText(tax) : "");
        }
        if ("purchase_invoice".equals(voucher.voucherType)) {
            String credit = voucher.transactionId == null ? "应付账款" : "银行存款";
            return "借：管理费用 " + moneyText(netAmount)
                + (tax.signum() > 0 ? "，应交税费-进项税额 " + moneyText(tax) : "")
                + "；贷：" + credit + " " + moneyText(amount);
        }
        if ("reimbursement".equals(voucher.voucherType)) {
            String owner = isBlank(voucher.expenseOwner) ? "员工" : voucher.expenseOwner;
            return "借：管理费用 " + moneyText(amount) + "；贷：其他应付款-" + owner + " " + moneyText(amount);
        }
        if ("tax_receipt".equals(voucher.voucherType)) {
            return "借：应交税费 " + moneyText(amount) + "；贷：银行存款 " + moneyText(amount);
        }
        String credit = voucher.transactionId == null ? "应付账款" : "银行存款";
        return "借：管理费用 " + moneyText(amount) + "；贷：" + credit + " " + moneyText(amount);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String moneyText(BigDecimal value) {
        return money(value).stripTrailingZeros().toPlainString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
