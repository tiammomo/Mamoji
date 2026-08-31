package com.mamoji.evidence.infrastructure;

import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.ReceiptVoucher;
import com.mamoji.evidence.domain.ReceiptVoucherDraft;
import com.mamoji.platform.identity.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Repairs legacy evidence data and owns the optional local demo receipt dataset. */
@Component
public class ReceiptVoucherDataInitializer {
    private final ReceiptVoucherRepository receiptVouchers;
    private final EnterpriseStore enterpriseStore;
    private final InMemoryStore coreStore;
    private final String bootstrapMode;

    public ReceiptVoucherDataInitializer(
        ReceiptVoucherRepository receiptVouchers,
        EnterpriseStore enterpriseStore,
        InMemoryStore coreStore,
        @Value("${mamoji.bootstrap.mode:demo}") String bootstrapMode
    ) {
        this.receiptVouchers = receiptVouchers;
        this.enterpriseStore = enterpriseStore;
        this.coreStore = coreStore;
        this.bootstrapMode = bootstrapMode == null ? "demo" : bootstrapMode.trim().toLowerCase(Locale.ROOT);
    }

    @PostConstruct
    void initialize() {
        receiptVouchers.repairLegacyDefaults();
        if ("bootstrap".equals(bootstrapMode)) {
            return;
        }
        Optional<Company> company = enterpriseStore.sortedCompanies().stream()
            .filter(candidate -> "company".equals(candidate.entityType))
            .min(Comparator.comparing(candidate -> candidate.id));
        Optional<User> owner = coreStore.sortedUsers().stream()
            .filter(user -> user.role == 1)
            .min(Comparator.comparing(user -> user.id))
            .or(() -> coreStore.sortedUsers().stream().min(Comparator.comparing(user -> user.id)));
        if (company.isEmpty() || owner.isEmpty()) {
            return;
        }
        long companyId = company.get().id;
        long ownerId = owner.get().id;
        if (receiptVouchers.findByCompany(companyId).isEmpty()) {
            seedReceiptVouchers(companyId, ownerId);
        }
        seedReceiptAuditLogs(owner.get());
    }

    private void seedReceiptVouchers(long companyId, long ownerId) {
        create(
            companyId, ownerId, "INV-202606-001", "客户项目回款销项发票", "sales_invoice", "income", "客户项目方",
            "2800", "0", "2026-06-05", "2026-07-15", "verified", "invoice-202606-001.pdf", 184320,
            "application/pdf", "low", "已与项目回款匹配",
            voucher -> {
                voucher.invoiceCheckStatus = "verified";
                voucher.businessPurpose = "项目服务收入";
            }
        );
        create(
            companyId, ownerId, "AR-202606-001", "项目已交付待首期回款", "sales_invoice", "income", "客户项目方",
            "26000", "0", "2026-06-06", "2026-06-12", "pending_review", null, 0, null, "medium",
            "验收完成，首期款待客户付款并匹配银行回单",
            voucher -> {
                voucher.invoiceCheckStatus = "pending";
                voucher.businessPurpose = "交付后待开票/待收款";
            }
        );
        create(
            companyId, ownerId, "AR-202606-002", "项目尾款待回款", "sales_invoice", "income", "客户项目方",
            "8800", "0", "2026-06-01", "2026-06-20", "pending_review", null, 0, null, "medium",
            "尾款按合同约定在交付后十五日内回款",
            voucher -> {
                voucher.invoiceCheckStatus = "pending";
                voucher.businessPurpose = "项目尾款应收";
            }
        );
        create(
            companyId, ownerId, "VAT-202606-012", "办公采购进项发票", "purchase_invoice", "expense", "办公用品供应商",
            "899", "26.97", "2026-06-03", null, "linked", "purchase-keyboard.jpg", 728436, "image/jpeg", "low",
            "可用于成本归档",
            voucher -> {
                voucher.invoiceCheckStatus = "verified";
                voucher.deductionStatus = "deductible";
                voucher.businessPurpose = "办公采购";
                voucher.expenseOwner = "财务行政";
            }
        );
        create(
            companyId, ownerId, "BANK-202606-003", "银行回单-房租付款", "bank_slip", "expense", "联合办公空间",
            "3200", "0", "2026-06-05", null, "verified", "rent-bank-slip.png", 566214, "image/png", "medium",
            "待关联租金周期事项",
            voucher -> {
                voucher.businessPurpose = "办公场地租金";
                voucher.expenseOwner = "财务行政";
            }
        );
        create(
            companyId, ownerId, "REIM-202605-008", "家庭代垫报销凭证", "reimbursement", "expense", "家庭资产主体",
            "2680", "0", "2026-05-20", null, "archived", "reimbursement-advance.pdf", 245761,
            "application/pdf", "low", "与主体往来记录一致",
            voucher -> {
                voucher.reimbursementStatus = "archived";
                voucher.businessPurpose = "家庭主体代垫公司费用";
                voucher.expenseOwner = "创始人";
            }
        );
        create(
            companyId, ownerId, "CTR-202606-002", "SaaS 年度订阅合同付款证明", "contract", "expense", "SaaS 服务商",
            "7800", "0", "2026-06-01", "2026-06-30", "pending_review", null, 0, null, "high",
            "金额较大，需补充合同附件和付款回单",
            voucher -> {
                voucher.businessPurpose = "SaaS 年费";
                voucher.expenseOwner = "产品研发";
            }
        );
        create(
            companyId, ownerId, "TAX-202607-001", "税费申报回执待补充", "tax_receipt", "expense", "税务机关",
            "8458.08", "0", "2026-07-15", "2026-07-15", "pending_review", null, 0, null, "medium",
            "待完成申报后上传回执",
            voucher -> voucher.businessPurpose = "2026-06 税费申报"
        );
    }

    private ReceiptVoucher create(
        long companyId,
        long ownerId,
        String voucherNo,
        String title,
        String voucherType,
        String direction,
        String counterparty,
        String amount,
        String taxAmount,
        String issueDate,
        String dueDate,
        String status,
        String fileName,
        long fileSize,
        String fileType,
        String riskLevel,
        String note,
        Consumer<ReceiptVoucher> customize
    ) {
        ReceiptVoucher voucher = receiptVouchers.insert(new ReceiptVoucherDraft(
            companyId,
            null,
            voucherNo,
            title,
            voucherType,
            direction,
            counterparty,
            new BigDecimal(amount),
            new BigDecimal(taxAmount),
            issueDate,
            dueDate,
            status,
            fileName,
            fileSize,
            fileType,
            riskLevel,
            note,
            ownerId
        ));
        customize.accept(voucher);
        receiptVouchers.save(voucher);
        return voucher;
    }

    private void seedReceiptAuditLogs(User owner) {
        boolean alreadySeeded = enterpriseStore.sortedAuditLogs().stream()
            .anyMatch(log -> "receipt_voucher".equals(log.entityType));
        if (alreadySeeded) {
            return;
        }
        receiptVouchers.findAll().stream()
            .sorted(Comparator.comparing(voucher -> voucher.id))
            .forEach(voucher -> enterpriseStore.auditLog(
                voucher.companyId,
                "receipt_voucher",
                voucher.id,
                "seed",
                "系统初始化票据「" + voucher.title + "」",
                owner.id,
                owner.nickname
            ));
    }
}
