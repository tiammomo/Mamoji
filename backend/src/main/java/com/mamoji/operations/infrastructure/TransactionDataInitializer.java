package com.mamoji.operations.infrastructure;

import com.mamoji.platform.tenant.Company;
import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.finance.domain.Account;
import com.mamoji.operations.application.CategoryRepository;
import com.mamoji.operations.application.TransactionQueryRepository;
import com.mamoji.operations.application.TransactionWriteRepository;
import com.mamoji.operations.domain.Category;
import com.mamoji.operations.domain.TransactionRecord;
import com.mamoji.platform.tenant.CompanyRepository;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** Owns optional transaction demo data after company-scoped accounting data exists. */
@Component
@ConditionalOnProperty(name = "mamoji.bootstrap.mode", havingValue = "demo", matchIfMissing = true)
@DependsOn({"accountDataInitializer", "categoryDataInitializer"})
public class TransactionDataInitializer {
    private final TransactionQueryRepository transactions;
    private final TransactionWriteRepository transactionWrites;
    private final FinanceRepository finances;
    private final CategoryRepository categories;
    private final CompanyRepository companies;

    public TransactionDataInitializer(
        TransactionQueryRepository transactions,
        TransactionWriteRepository transactionWrites,
        FinanceRepository finances,
        CategoryRepository categories,
        CompanyRepository companies
    ) {
        this.transactions = transactions;
        this.transactionWrites = transactionWrites;
        this.finances = finances;
        this.categories = categories;
        this.companies = companies;
    }

    @PostConstruct
    void initialize() {
        Optional<Company> company = companies.findAll().stream()
            .filter(candidate -> "company".equals(candidate.entityType))
            .min(Comparator.comparing(candidate -> candidate.id));
        if (company.isEmpty()) {
            return;
        }
        Company subject = company.get();
        if (!transactions.findAll(subject.ownerId, subject.id).isEmpty()) {
            return;
        }

        Map<String, Account> accounts = finances.findAccounts(subject.ownerId, subject.id).stream()
            .collect(Collectors.toMap(account -> account.name, Function.identity(), (left, right) -> left));
        Map<String, Category> categoryByName = categories.findAll(subject.ownerId, subject.id, null).stream()
            .collect(Collectors.toMap(category -> category.name, Function.identity(), (left, right) -> left));
        Account cash = accounts.get("公司现金备用金");
        Account bank = accounts.get("公司基本户");
        List<String> requiredCategories = List.of(
            "主营业务收入", "团队餐饮", "差旅交通", "办公采购", "客户退款", "离职补偿"
        );
        if (cash == null || bank == null || !categoryByName.keySet().containsAll(requiredCategories)) {
            return;
        }

        create(subject, bank, categoryByName.get("主营业务收入"), 1, "15000.00", 4, "客户项目回款", "income");
        create(subject, bank, categoryByName.get("主营业务收入"), 1, "22000.00", 5,
            "项目交付待回款：ERP 二期验收，预计下月到账", "pending-income");
        create(subject, cash, categoryByName.get("团队餐饮"), 2, "68.50", 1, "团队工作餐", "team-meal");
        create(subject, cash, categoryByName.get("差旅交通"), 2, "25.00", 2, "市内交通", "travel");
        create(subject, bank, categoryByName.get("办公采购"), 2, "899.00", 3, "办公键盘和配件", "procurement");
        create(subject, bank, categoryByName.get("客户退款"), 2, "1200.00", 2,
            "客户退款：交付范围调整，冲减收入", "customer-refund");
        create(subject, bank, categoryByName.get("离职补偿"), 2, "18000.00", 6,
            "离职补偿：N+1 经济补偿", "severance");
    }

    private void create(
        Company company,
        Account account,
        Category category,
        int type,
        String amount,
        int daysAgo,
        String note,
        String key
    ) {
        String now = OffsetDateTime.now().toString();
        TransactionRecord transaction = new TransactionRecord();
        transaction.companyId = company.id;
        transaction.userId = company.ownerId;
        transaction.familyId = account.ledgerId;
        transaction.type = type;
        transaction.amount = new BigDecimal(amount);
        transaction.categoryId = category.id;
        transaction.accountId = account.id;
        transaction.date = LocalDate.now().minusDays(daysAgo).toString();
        transaction.note = note;
        transaction.refundedAmount = BigDecimal.ZERO;
        transaction.isRefundable = type == 2;
        transaction.createdAt = now;
        transaction.updatedAt = now;
        transaction.idempotencyKey = "demo:" + key;
        transactionWrites.insert(transaction);
    }
}
