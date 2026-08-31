package com.mamoji.finance.application;

import com.mamoji.common.PayloadReader;
import com.mamoji.domain.Models.Company;
import com.mamoji.finance.domain.Account;
import com.mamoji.finance.domain.Ledger;
import com.mamoji.platform.identity.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.service.OutboxEventService;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Finance application boundary for account lifecycle and account summaries. */
@Service
public class AccountApplicationService {
    private final FinanceRepository repository;
    private final EnterpriseStore enterpriseStore;
    private final AccessControlService accessControl;
    private final OutboxEventService outboxEventService;

    public AccountApplicationService(
        FinanceRepository repository,
        EnterpriseStore enterpriseStore,
        AccessControlService accessControl,
        OutboxEventService outboxEventService
    ) {
        this.repository = repository;
        this.enterpriseStore = enterpriseStore;
        this.accessControl = accessControl;
        this.outboxEventService = outboxEventService;
    }

    @Transactional(readOnly = true)
    public List<Account> listAccounts(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        Period period = currentPeriod();
        return repository.findAccountsWithMetrics(user.id, company.id, period.start(), period.endExclusive()).stream()
            .map(this::attachCalculatedFields)
            .toList();
    }

    @Transactional(readOnly = true)
    public Account getAccount(String authorization, long id, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Period period = currentPeriod();
        Account account = repository.findAccountWithMetrics(id, period.start(), period.endExclusive())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? account.companyId : companyId);
        assertScopedOwner(account, user.id, company.id);
        return attachCalculatedFields(account);
    }

    public Account getAccountForUpdate(String authorization, long id, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Account account = repository.findAccountForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? account.companyId : companyId);
        assertScopedOwner(account, user.id, company.id);
        return account;
    }

    @Transactional
    public Account createAccount(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(
            user,
            PayloadReader.optionalLong(body.get("companyId")).orElse(null)
        );
        Ledger ledger = repository.ensureAccountingLedger(user.id, company.id, company.currency, company.name);
        Account account = newAccount(user.id, company.id, ledger.id, body);
        repository.insertAccount(account);
        account.includeInNetWorth = PayloadReader.bool(body.get("includeInNetWorth"), true);
        applyAccountFields(account, body);
        repository.updateAccount(account);
        audit(company.id, account.id, "create", "创建资金账户: " + account.name, user);
        return reloadedAccount(account);
    }

    @Transactional
    public Account updateAccount(
        String authorization,
        long id,
        Long companyId,
        Map<String, Object> body
    ) {
        User user = accessControl.requireUser(authorization);
        Account existing = repository.findAccountForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? existing.companyId : companyId);
        assertScopedOwner(existing, user.id, company.id);
        Account account = copyAccount(existing);
        if (body.containsKey("name")) account.name = PayloadReader.text(body.get("name"));
        if (body.containsKey("type")) account.type = PayloadReader.text(body.get("type"));
        if (body.containsKey("subType")) account.subType = PayloadReader.nullableText(body.get("subType"));
        if (body.containsKey("bank")) account.bank = PayloadReader.nullableText(body.get("bank"));
        if (body.containsKey("balance")) {
            account.balance = PayloadReader.number(body.get("balance"), account.balance);
            if (!body.containsKey("availableBalance")) {
                account.availableBalance = nullToZero(existing.availableBalance)
                    .add(account.balance.subtract(existing.balance));
            }
        }
        if (body.containsKey("includeInNetWorth")) {
            account.includeInNetWorth = PayloadReader.bool(
                body.get("includeInNetWorth"),
                account.includeInNetWorth
            );
        }
        applyAccountFields(account, body);
        account.updatedAt = OffsetDateTime.now().toString();
        repository.updateAccount(account);
        audit(company.id, account.id, "update", "更新资金账户: " + account.name, user);
        return reloadedAccount(account);
    }

    @Transactional
    public void deleteAccount(String authorization, long id, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Account account = repository.findAccountForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? account.companyId : companyId);
        assertScopedOwner(account, user.id, company.id);
        if (repository.accountHasTransactions(account.id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account has transactions");
        }
        repository.deleteAccount(account);
        audit(company.id, account.id, "delete", "删除资金账户: " + account.name, user);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> accountSummary(String authorization, Long companyId) {
        List<Account> accounts = listAccounts(authorization, companyId);
        BigDecimal liabilities = accounts.stream()
            .filter(account -> account.includeInNetWorth)
            .filter(account -> "debt".equals(account.type) || "credit".equals(account.type))
            .map(account -> account.balance.abs())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal assets = accounts.stream()
            .filter(account -> account.includeInNetWorth)
            .filter(account -> !"debt".equals(account.type) && !"credit".equals(account.type))
            .map(account -> account.balance.max(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal availableBalance = accounts.stream()
            .filter(account -> !"debt".equals(account.type))
            .map(account -> account.availableBalance)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal frozenAmount = accounts.stream()
            .map(account -> account.frozenAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditLimit = accounts.stream()
            .filter(account -> "credit".equals(account.type))
            .map(account -> account.creditLimit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalAssets", assets);
        summary.put("totalLiabilities", liabilities);
        summary.put("netWorth", assets.subtract(liabilities));
        summary.put("availableBalance", availableBalance);
        summary.put("frozenAmount", frozenAmount);
        summary.put("creditLimit", creditLimit);
        summary.put("currentMonthIncome", sum(accounts, account -> account.monthlyIncome));
        summary.put("currentMonthExpense", sum(accounts, account -> account.monthlyExpense));
        summary.put("accountCount", accounts.size());
        summary.put("activeAccountCount", accounts.stream().filter(account -> account.status == 1).count());
        summary.put("pendingReconciliationCount", accounts.stream()
            .filter(account -> !"reconciled".equals(account.reconciliationStatus)).count());
        summary.put("highRiskCount", accounts.stream()
            .filter(account -> "high".equals(account.riskLevel) || "critical".equals(account.riskLevel)).count());
        return summary;
    }

    private Account newAccount(long userId, long companyId, long ledgerId, Map<String, Object> body) {
        Account account = new Account();
        account.userId = userId;
        account.companyId = companyId;
        account.ledgerId = ledgerId;
        account.name = PayloadReader.textOr(body.get("name"), "新账户");
        account.type = PayloadReader.textOr(body.get("type"), "cash");
        account.subType = PayloadReader.nullableText(body.get("subType"));
        account.bank = PayloadReader.nullableText(body.get("bank"));
        account.openingBank = account.bank;
        account.currency = "CNY";
        account.balance = PayloadReader.number(body.get("balance"), BigDecimal.ZERO);
        account.availableBalance = account.balance;
        account.creditLimit = "credit".equals(account.type)
            ? account.balance.abs().max(new BigDecimal("20000"))
            : BigDecimal.ZERO;
        account.frozenAmount = BigDecimal.ZERO;
        account.includeInNetWorth = true;
        account.status = 1;
        account.openedAt = LocalDate.now().minusMonths(6).toString();
        account.lastReconciledAt = LocalDate.now().minusDays("cash".equals(account.type) ? 8 : 2).toString();
        account.ownerName = "财务负责人";
        account.purpose = accountPurpose(account.type);
        account.reconciliationStatus = "cash".equals(account.type) ? "pending" : "reconciled";
        account.riskLevel = "low";
        account.createdAt = OffsetDateTime.now().toString();
        account.updatedAt = account.createdAt;
        return account;
    }

    private void applyAccountFields(Account account, Map<String, Object> body) {
        if (body.containsKey("accountNo")) account.accountNo = PayloadReader.nullableText(body.get("accountNo"));
        if (body.containsKey("openingBank")) {
            account.openingBank = PayloadReader.nullableText(body.get("openingBank"));
        }
        if (body.containsKey("currency")) account.currency = PayloadReader.textOr(body.get("currency"), "CNY");
        if (account.currency == null || account.currency.isBlank()) account.currency = "CNY";
        if (body.containsKey("availableBalance")) {
            account.availableBalance = PayloadReader.number(body.get("availableBalance"), account.availableBalance);
        } else if (account.availableBalance == null) {
            account.availableBalance = account.balance.subtract(nullToZero(account.frozenAmount));
        }
        if (body.containsKey("creditLimit")) {
            account.creditLimit = PayloadReader.number(body.get("creditLimit"), account.creditLimit);
        } else if (account.creditLimit == null) {
            account.creditLimit = BigDecimal.ZERO;
        }
        if (body.containsKey("frozenAmount")) {
            account.frozenAmount = PayloadReader.number(body.get("frozenAmount"), account.frozenAmount);
        } else if (account.frozenAmount == null) {
            account.frozenAmount = BigDecimal.ZERO;
        }
        if (body.containsKey("openedAt")) account.openedAt = PayloadReader.nullableText(body.get("openedAt"));
        if (body.containsKey("lastReconciledAt")) {
            account.lastReconciledAt = PayloadReader.nullableText(body.get("lastReconciledAt"));
        }
        if (body.containsKey("ownerName")) account.ownerName = PayloadReader.nullableText(body.get("ownerName"));
        else if (account.ownerName == null || account.ownerName.isBlank()) account.ownerName = "财务负责人";
        if (body.containsKey("purpose")) account.purpose = PayloadReader.nullableText(body.get("purpose"));
        else if (account.purpose == null || account.purpose.isBlank()) account.purpose = accountPurpose(account.type);
        if (body.containsKey("reconciliationStatus")) {
            account.reconciliationStatus = normalizeReconciliationStatus(
                PayloadReader.text(body.get("reconciliationStatus"))
            );
        } else if (account.reconciliationStatus == null || account.reconciliationStatus.isBlank()) {
            account.reconciliationStatus = "pending";
        }
        if (body.containsKey("status")) {
            account.status = PayloadReader.intValue(body.get("status"), account.status);
        }
        account.riskLevel = accountRisk(account);
    }

    private Account reloadedAccount(Account fallback) {
        Period period = currentPeriod();
        return repository.findAccountWithMetrics(fallback.id, period.start(), period.endExclusive())
            .map(this::attachCalculatedFields)
            .orElseGet(() -> attachCalculatedFields(fallback));
    }

    private Account attachCalculatedFields(Account account) {
        account.availableBalance = nullToZero(account.availableBalance);
        account.creditLimit = nullToZero(account.creditLimit);
        account.frozenAmount = nullToZero(account.frozenAmount);
        account.monthlyIncome = nullToZero(account.monthlyIncome);
        account.monthlyExpense = nullToZero(account.monthlyExpense).max(BigDecimal.ZERO);
        account.currentMonthNetFlow = account.monthlyIncome.subtract(account.monthlyExpense);
        account.riskLevel = accountRisk(account);
        return account;
    }

    private String accountRisk(Account account) {
        if (account.status == 0) return "medium";
        if ("credit".equals(account.type)
            && nullToZero(account.creditLimit).compareTo(BigDecimal.ZERO) > 0
            && account.balance.abs().compareTo(account.creditLimit.multiply(new BigDecimal("0.9"))) >= 0) {
            return "high";
        }
        if (nullToZero(account.availableBalance).compareTo(BigDecimal.ZERO) < 0
            || "exception".equals(account.reconciliationStatus)) return "high";
        if (isReconciliationStale(account)
            || nullToZero(account.frozenAmount).compareTo(BigDecimal.ZERO) > 0
            || "pending".equals(account.reconciliationStatus)) return "medium";
        return "low";
    }

    private boolean isReconciliationStale(Account account) {
        if (account.lastReconciledAt == null || account.lastReconciledAt.isBlank()) return true;
        try {
            return LocalDate.parse(account.lastReconciledAt).isBefore(LocalDate.now().minusDays(15));
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private String normalizeReconciliationStatus(String value) {
        return switch (value) {
            case "reconciled", "pending", "exception" -> value;
            default -> "pending";
        };
    }

    private String accountPurpose(String type) {
        return switch (type) {
            case "cash" -> "零星备用金和小额报销";
            case "bank" -> "客户回款、供应商付款和税费缴纳";
            case "credit" -> "短期周转和线上订阅付款";
            case "digital" -> "线上支付和平台收款";
            case "investment" -> "闲置资金理财和收益管理";
            case "debt" -> "借款、垫资和负债管理";
            default -> "企业资金账户";
        };
    }

    private void assertScopedOwner(Account account, long userId, long companyId) {
        if (account.userId != userId || !Objects.equals(account.companyId, companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private Account copyAccount(Account source) {
        Account target = new Account();
        try {
            for (var field : source.getClass().getFields()) field.set(target, field.get(source));
            return target;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to copy finance account", ex);
        }
    }

    private void audit(long companyId, long accountId, String action, String summary, User user) {
        enterpriseStore.auditLog(companyId, "account", accountId, action, summary, user.id, user.nickname);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        payload.put("actorName", user.nickname);
        payload.put("entityType", "account");
        payload.put("action", action);
        outboxEventService.publish(
            "accounting.account." + action,
            companyId,
            "account",
            accountId,
            user.id,
            payload
        );
    }

    private Period currentPeriod() {
        YearMonth month = YearMonth.now();
        return new Period(month.atDay(1), month.plusMonths(1).atDay(1));
    }

    private BigDecimal sum(
        List<Account> accounts,
        java.util.function.Function<Account, BigDecimal> extractor
    ) {
        return accounts.stream().map(extractor).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record Period(LocalDate start, LocalDate endExclusive) {
    }
}
