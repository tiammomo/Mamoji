package com.mamoji.finance.infrastructure;

import com.mamoji.domain.Models.Company;
import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.finance.domain.Account;
import com.mamoji.finance.domain.Ledger;
import com.mamoji.repository.EnterpriseStore;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** Owns optional account demo data after the company and its accounting ledger exist. */
@Component
@DependsOn("ledgerDataInitializer")
public class AccountDataInitializer {
    private final FinanceRepository finances;
    private final EnterpriseStore enterpriseStore;
    private final String bootstrapMode;

    public AccountDataInitializer(
        FinanceRepository finances,
        EnterpriseStore enterpriseStore,
        @Value("${mamoji.bootstrap.mode:demo}") String bootstrapMode
    ) {
        this.finances = finances;
        this.enterpriseStore = enterpriseStore;
        this.bootstrapMode = bootstrapMode == null ? "demo" : bootstrapMode.trim().toLowerCase(Locale.ROOT);
    }

    @PostConstruct
    void initialize() {
        if ("bootstrap".equals(bootstrapMode)) {
            return;
        }
        Optional<Company> company = enterpriseStore.sortedCompanies().stream()
            .filter(candidate -> "company".equals(candidate.entityType))
            .min(Comparator.comparingLong(candidate -> candidate.id));
        if (company.isEmpty()) {
            return;
        }
        Company subject = company.get();
        if (!finances.findAccounts(subject.ownerId, subject.id).isEmpty()) {
            return;
        }
        Ledger ledger = finances.ensureAccountingLedger(
            subject.ownerId,
            subject.id,
            subject.currency,
            subject.name
        );
        create(subject, ledger, "公司现金备用金", "cash", "备用金", null, "1200");
        create(subject, ledger, "公司基本户", "bank", "对公账户", "招商银行", "26300");
        create(subject, ledger, "企业信用卡", "credit", "信用卡", "招商银行", "1800");
    }

    private void create(
        Company company,
        Ledger ledger,
        String name,
        String type,
        String subType,
        String bank,
        String balance
    ) {
        BigDecimal openingBalance = new BigDecimal(balance);
        String now = OffsetDateTime.now().toString();
        Account account = new Account();
        account.companyId = company.id;
        account.userId = company.ownerId;
        account.ledgerId = ledger.id;
        account.name = name;
        account.type = type;
        account.subType = subType;
        account.bank = bank;
        account.openingBank = bank;
        account.currency = company.currency == null || company.currency.isBlank()
            ? "CNY"
            : company.currency.trim().toUpperCase(Locale.ROOT);
        account.balance = openingBalance;
        account.availableBalance = openingBalance;
        account.creditLimit = "credit".equals(type)
            ? openingBalance.abs().max(new BigDecimal("20000"))
            : BigDecimal.ZERO;
        account.frozenAmount = BigDecimal.ZERO;
        account.includeInNetWorth = true;
        account.status = 1;
        account.openedAt = LocalDate.now().minusMonths(6).toString();
        account.lastReconciledAt = LocalDate.now().minusDays("cash".equals(type) ? 8 : 2).toString();
        account.ownerName = "财务负责人";
        account.purpose = purpose(type);
        account.reconciliationStatus = "cash".equals(type) ? "pending" : "reconciled";
        account.riskLevel = "low";
        account.createdAt = now;
        account.updatedAt = now;
        finances.insertAccount(account);
    }

    private String purpose(String type) {
        return switch (type) {
            case "cash" -> "零星备用金和小额报销";
            case "bank" -> "客户回款、供应商付款和税费缴纳";
            case "credit" -> "短期周转和线上订阅付款";
            default -> "企业资金账户";
        };
    }
}
