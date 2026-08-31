package com.mamoji.finance.infrastructure;

import com.mamoji.domain.Models.Account;
import com.mamoji.domain.Models.Ledger;
import com.mamoji.domain.Models.LedgerMember;
import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.finance.domain.AccountReconciliation;
import com.mamoji.repository.InMemoryStore;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for finance-owned account and ledger persistence. */
@Repository
public class JdbcFinanceRepository implements FinanceRepository {
    private static final String ACCOUNT_METRICS_SELECT = """
        SELECT account.*,
               COALESCE(SUM(CASE
                   WHEN transaction_record.date >= ? AND transaction_record.date < ?
                    AND transaction_record.type = 1
                   THEN CAST(NULLIF(transaction_record.amount, '') AS NUMERIC) ELSE 0 END), 0) AS monthly_income,
               GREATEST(COALESCE(SUM(CASE
                   WHEN transaction_record.date >= ? AND transaction_record.date < ?
                    AND transaction_record.type = 2
                   THEN CAST(NULLIF(transaction_record.amount, '') AS NUMERIC)
                   WHEN transaction_record.date >= ? AND transaction_record.date < ?
                    AND transaction_record.type = 3
                   THEN -CAST(NULLIF(transaction_record.amount, '') AS NUMERIC) ELSE 0 END), 0), 0) AS monthly_expense,
               COUNT(transaction_record.id) AS transaction_count,
               MAX(transaction_record.date) AS last_transaction_date
        FROM accounts account
        LEFT JOIN transactions transaction_record
          ON transaction_record.account_id = account.id
         AND transaction_record.user_id = account.user_id
         AND transaction_record.company_id = account.company_id
        """;

    private final JdbcTemplate jdbc;
    private final InMemoryStore compatibilityStore;

    public JdbcFinanceRepository(JdbcTemplate jdbc, InMemoryStore compatibilityStore) {
        this.jdbc = jdbc;
        this.compatibilityStore = compatibilityStore;
    }

    @Override
    public List<Account> findAccounts(long userId, long companyId) {
        return jdbc.query(
            "SELECT * FROM accounts WHERE user_id = ? AND company_id = ? ORDER BY id",
            this::mapAccount,
            userId,
            companyId
        );
    }

    @Override
    public List<Account> findAccountsWithMetrics(
        long userId,
        long companyId,
        LocalDate periodStart,
        LocalDate periodEndExclusive
    ) {
        return jdbc.query(
            ACCOUNT_METRICS_SELECT + """
                WHERE account.user_id = ? AND account.company_id = ?
                GROUP BY account.id
                ORDER BY account.id
                """,
            this::mapAccountWithMetrics,
            periodStart.toString(),
            periodEndExclusive.toString(),
            periodStart.toString(),
            periodEndExclusive.toString(),
            periodStart.toString(),
            periodEndExclusive.toString(),
            userId,
            companyId
        );
    }

    @Override
    public Optional<Account> findAccountWithMetrics(
        long id,
        LocalDate periodStart,
        LocalDate periodEndExclusive
    ) {
        return jdbc.query(
            ACCOUNT_METRICS_SELECT + " WHERE account.id = ? GROUP BY account.id",
            this::mapAccountWithMetrics,
            periodStart.toString(),
            periodEndExclusive.toString(),
            periodStart.toString(),
            periodEndExclusive.toString(),
            periodStart.toString(),
            periodEndExclusive.toString(),
            id
        ).stream().findFirst();
    }

    @Override
    public Optional<Account> findAccount(long id) {
        return jdbc.query("SELECT * FROM accounts WHERE id = ?", this::mapAccount, id).stream().findFirst();
    }

    @Override
    public Optional<Account> findAccountForUpdate(long id) {
        return jdbc.query(
            "SELECT * FROM accounts WHERE id = ? FOR UPDATE",
            this::mapAccount,
            id
        ).stream().findFirst();
    }

    @Override
    public Account insertAccount(Account account) {
        account.version = 0;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO accounts (
                    name, type, sub_type, bank, account_no, opening_bank, currency, balance,
                    available_balance, credit_limit, frozen_amount, include_in_net_worth,
                    user_id, ledger_id, status, opened_at, last_reconciled_at, owner_name,
                    purpose, reconciliation_status, risk_level, created_at, updated_at, company_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new String[] { "id" });
            bindAccountInsert(statement, account);
            return statement;
        }, keyHolder);
        account.id = generatedId(keyHolder, "account");
        compatibilityStore.synchronizeAccountAfterCommit(account);
        return account;
    }

    @Override
    public void updateAccount(Account account) {
        int updated = jdbc.update("""
            UPDATE accounts
            SET name = ?, type = ?, sub_type = ?, bank = ?, account_no = ?, opening_bank = ?, currency = ?,
                balance = ?, available_balance = ?, credit_limit = ?, frozen_amount = ?, include_in_net_worth = ?,
                user_id = ?, ledger_id = ?, status = ?, opened_at = ?, last_reconciled_at = ?, owner_name = ?,
                purpose = ?, reconciliation_status = ?, risk_level = ?, created_at = ?, updated_at = ?, company_id = ?,
                version = version + 1
            WHERE id = ? AND version = ?
            """,
            account.name,
            account.type,
            account.subType,
            account.bank,
            account.accountNo,
            account.openingBank,
            account.currency,
            moneyText(account.balance),
            moneyText(account.availableBalance),
            moneyText(account.creditLimit),
            moneyText(account.frozenAmount),
            account.includeInNetWorth ? 1 : 0,
            account.userId,
            account.ledgerId,
            account.status,
            account.openedAt,
            account.lastReconciledAt,
            account.ownerName,
            account.purpose,
            account.reconciliationStatus,
            account.riskLevel,
            account.createdAt,
            account.updatedAt,
            account.companyId,
            account.id,
            account.version
        );
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Account was changed by another request: " + account.id);
        }
        account.version++;
        compatibilityStore.synchronizeAccountAfterCommit(account);
    }

    @Override
    public boolean accountHasTransactions(long accountId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transactions WHERE account_id = ?",
            Integer.class,
            accountId
        );
        return count != null && count > 0;
    }

    @Override
    public void deleteAccount(Account account) {
        int deleted = jdbc.update(
            "DELETE FROM accounts WHERE id = ? AND version = ?",
            account.id,
            account.version
        );
        if (deleted != 1) {
            throw new OptimisticLockingFailureException("Account was changed by another request: " + account.id);
        }
        compatibilityStore.removeAccountFromCompatibilityViewAfterCommit(account.id);
    }

    @Override
    public List<AccountReconciliation> findAccountReconciliations(
        long accountId,
        long companyId,
        long userId,
        int limit
    ) {
        return jdbc.query("""
            SELECT * FROM account_reconciliations
            WHERE account_id = ? AND company_id = ? AND user_id = ?
            ORDER BY statement_date DESC, id DESC
            LIMIT ?
            """, this::mapAccountReconciliation, accountId, companyId, userId, limit);
    }

    @Override
    public AccountReconciliation insertAccountReconciliation(AccountReconciliation reconciliation) {
        return jdbc.queryForObject("""
            INSERT INTO account_reconciliations (
                company_id, user_id, account_id, statement_date, statement_balance, system_balance,
                difference, status, note, created_by, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING *
            """, this::mapAccountReconciliation, reconciliation.companyId(), reconciliation.userId(),
            reconciliation.accountId(), reconciliation.statementDate(),
            moneyText(reconciliation.statementBalance()), moneyText(reconciliation.systemBalance()),
            moneyText(reconciliation.difference()), reconciliation.status(), reconciliation.note(),
            reconciliation.createdBy(), reconciliation.createdAt());
    }

    @Override
    public Optional<Ledger> findLedger(long id) {
        return jdbc.query("SELECT * FROM ledgers WHERE id = ?", this::mapLedger, id).stream().findFirst();
    }

    @Override
    public Optional<Ledger> findLedgerForUpdate(long id) {
        return jdbc.query(
            "SELECT * FROM ledgers WHERE id = ? FOR UPDATE",
            this::mapLedger,
            id
        ).stream().findFirst();
    }

    @Override
    public List<Ledger> findOwnedLedgers(long ownerId, long companyId) {
        return jdbc.query(
            "SELECT * FROM ledgers WHERE owner_id = ? AND company_id = ? ORDER BY is_default DESC, id",
            this::mapLedger,
            ownerId,
            companyId
        );
    }

    @Override
    public List<Ledger> findAccessibleLedgers(long userId, long companyId) {
        return jdbc.query("""
            SELECT DISTINCT ledger.*
            FROM ledgers ledger
            LEFT JOIN ledger_members member ON member.ledger_id = ledger.id AND member.user_id = ?
            WHERE ledger.company_id = ? AND (ledger.owner_id = ? OR member.user_id IS NOT NULL)
            ORDER BY ledger.id
            """, this::mapLedger, userId, companyId, userId);
    }

    @Override
    public Ledger insertLedger(Ledger ledger) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledgers (
                    name, description, currency, owner_id, is_default, status,
                    created_at, updated_at, company_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new String[] { "id" });
            statement.setString(1, ledger.name);
            statement.setString(2, ledger.description);
            statement.setString(3, ledger.currency);
            statement.setLong(4, ledger.ownerId);
            statement.setInt(5, ledger.isDefault ? 1 : 0);
            statement.setInt(6, ledger.status);
            statement.setString(7, ledger.createdAt);
            statement.setString(8, ledger.updatedAt);
            setLongOrNull(statement, 9, ledger.companyId);
            return statement;
        }, keyHolder);
        ledger.id = generatedId(keyHolder, "ledger");
        compatibilityStore.synchronizeLedgerAfterCommit(ledger);
        return ledger;
    }

    @Override
    public Ledger ensureAccountingLedger(long ownerId, long companyId, String currency, String subjectName) {
        List<Ledger> ledgers = findOwnedLedgers(ownerId, companyId);
        Ledger ledger = ledgers.stream()
            .filter(candidate -> candidate.isDefault)
            .min(Comparator.comparingLong(candidate -> candidate.id))
            .or(() -> ledgers.stream().min(Comparator.comparingLong(candidate -> candidate.id)))
            .orElseGet(() -> insertLedger(newLedger(ownerId, companyId, currency, subjectName, true)));
        if (!ledgerMemberExists(ledger.id, ownerId)) {
            MemberProfile profile = findMemberProfile(ownerId).orElse(new MemberProfile(ownerId, null, null));
            insertLedgerMember(newMember(ledger.id, profile, "owner"));
        }
        return ledger;
    }

    @Override
    public List<LedgerMember> findLedgerMembers(long ledgerId) {
        return jdbc.query(
            "SELECT * FROM ledger_members WHERE ledger_id = ? ORDER BY id",
            this::mapLedgerMember,
            ledgerId
        );
    }

    @Override
    public boolean ledgerMemberExists(long ledgerId, long userId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ledger_members WHERE ledger_id = ? AND user_id = ?",
            Integer.class,
            ledgerId,
            userId
        );
        return count != null && count > 0;
    }

    @Override
    public Optional<MemberProfile> findMemberProfile(long userId) {
        return jdbc.query(
            "SELECT id, nickname, avatar FROM users WHERE id = ?",
            (rs, rowNum) -> new MemberProfile(
                rs.getLong("id"),
                rs.getString("nickname"),
                rs.getString("avatar")
            ),
            userId
        ).stream().findFirst();
    }

    @Override
    public LedgerMember insertLedgerMember(LedgerMember member) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger_members (ledger_id, user_id, role, nickname, avatar, joined_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, new String[] { "id" });
            statement.setLong(1, member.ledgerId);
            statement.setLong(2, member.userId);
            statement.setString(3, member.role);
            statement.setString(4, member.nickname);
            statement.setString(5, member.avatar);
            statement.setString(6, member.joinedAt);
            return statement;
        }, keyHolder);
        member.id = generatedId(keyHolder, "ledger member");
        compatibilityStore.synchronizeLedgerMemberAfterCommit(member);
        return member;
    }

    @Override
    public void deleteLedgerMember(long ledgerId, long userId) {
        jdbc.update("DELETE FROM ledger_members WHERE ledger_id = ? AND user_id = ?", ledgerId, userId);
        compatibilityStore.removeLedgerMemberFromCompatibilityViewAfterCommit(ledgerId, userId);
    }

    private Account mapAccountWithMetrics(ResultSet rs, int rowNum) throws SQLException {
        Account account = mapAccount(rs, rowNum);
        account.monthlyIncome = money(rs.getString("monthly_income"));
        account.monthlyExpense = money(rs.getString("monthly_expense"));
        account.currentMonthNetFlow = account.monthlyIncome.subtract(account.monthlyExpense);
        account.transactionCount = rs.getLong("transaction_count");
        account.lastTransactionDate = rs.getString("last_transaction_date");
        return account;
    }

    private Account mapAccount(ResultSet rs, int rowNum) throws SQLException {
        Account account = new Account();
        account.id = rs.getLong("id");
        account.version = rs.getLong("version");
        account.companyId = nullableLong(rs, "company_id");
        account.name = rs.getString("name");
        account.type = rs.getString("type");
        account.subType = rs.getString("sub_type");
        account.bank = rs.getString("bank");
        account.accountNo = rs.getString("account_no");
        account.openingBank = rs.getString("opening_bank");
        account.currency = textOr(rs.getString("currency"), "CNY");
        account.balance = money(rs.getString("balance"));
        account.availableBalance = money(rs.getString("available_balance"));
        account.creditLimit = money(rs.getString("credit_limit"));
        account.frozenAmount = money(rs.getString("frozen_amount"));
        account.includeInNetWorth = rs.getInt("include_in_net_worth") == 1;
        account.userId = rs.getLong("user_id");
        account.ledgerId = nullableLong(rs, "ledger_id");
        account.status = rs.getInt("status");
        account.openedAt = rs.getString("opened_at");
        account.lastReconciledAt = rs.getString("last_reconciled_at");
        account.ownerName = rs.getString("owner_name");
        account.purpose = rs.getString("purpose");
        account.reconciliationStatus = textOr(rs.getString("reconciliation_status"), "pending");
        account.riskLevel = textOr(rs.getString("risk_level"), "low");
        account.createdAt = rs.getString("created_at");
        account.updatedAt = rs.getString("updated_at");
        account.monthlyIncome = BigDecimal.ZERO;
        account.monthlyExpense = BigDecimal.ZERO;
        account.currentMonthNetFlow = BigDecimal.ZERO;
        return account;
    }

    private Ledger mapLedger(ResultSet rs, int rowNum) throws SQLException {
        Ledger ledger = new Ledger();
        ledger.id = rs.getLong("id");
        ledger.companyId = nullableLong(rs, "company_id");
        ledger.name = rs.getString("name");
        ledger.description = rs.getString("description");
        ledger.currency = rs.getString("currency");
        ledger.ownerId = rs.getLong("owner_id");
        ledger.isDefault = rs.getInt("is_default") == 1;
        ledger.status = rs.getInt("status");
        ledger.createdAt = rs.getString("created_at");
        ledger.updatedAt = rs.getString("updated_at");
        return ledger;
    }

    private LedgerMember mapLedgerMember(ResultSet rs, int rowNum) throws SQLException {
        LedgerMember member = new LedgerMember();
        member.id = rs.getLong("id");
        member.ledgerId = rs.getLong("ledger_id");
        member.userId = rs.getLong("user_id");
        member.role = rs.getString("role");
        member.nickname = rs.getString("nickname");
        member.avatar = rs.getString("avatar");
        member.joinedAt = rs.getString("joined_at");
        return member;
    }

    private AccountReconciliation mapAccountReconciliation(ResultSet rs, int rowNum) throws SQLException {
        return new AccountReconciliation(
            rs.getLong("id"),
            rs.getLong("company_id"),
            rs.getLong("user_id"),
            rs.getLong("account_id"),
            rs.getString("statement_date"),
            money(rs.getString("statement_balance")),
            money(rs.getString("system_balance")),
            money(rs.getString("difference")),
            rs.getString("status"),
            rs.getString("note"),
            rs.getLong("created_by"),
            rs.getString("created_at")
        );
    }

    private void bindAccountInsert(PreparedStatement statement, Account account) throws SQLException {
        statement.setString(1, account.name);
        statement.setString(2, account.type);
        statement.setString(3, account.subType);
        statement.setString(4, account.bank);
        statement.setString(5, account.accountNo);
        statement.setString(6, account.openingBank);
        statement.setString(7, account.currency);
        statement.setString(8, moneyText(account.balance));
        statement.setString(9, moneyText(account.availableBalance));
        statement.setString(10, moneyText(account.creditLimit));
        statement.setString(11, moneyText(account.frozenAmount));
        statement.setInt(12, account.includeInNetWorth ? 1 : 0);
        statement.setLong(13, account.userId);
        setLongOrNull(statement, 14, account.ledgerId);
        statement.setInt(15, account.status);
        statement.setString(16, account.openedAt);
        statement.setString(17, account.lastReconciledAt);
        statement.setString(18, account.ownerName);
        statement.setString(19, account.purpose);
        statement.setString(20, account.reconciliationStatus);
        statement.setString(21, account.riskLevel);
        statement.setString(22, account.createdAt);
        statement.setString(23, account.updatedAt);
        setLongOrNull(statement, 24, account.companyId);
    }

    private Ledger newLedger(
        long ownerId,
        long companyId,
        String currency,
        String subjectName,
        boolean isDefault
    ) {
        Ledger ledger = new Ledger();
        ledger.ownerId = ownerId;
        ledger.companyId = companyId;
        ledger.name = textOr(subjectName, "经营主体") + "账本";
        ledger.description = "主体默认经营账本";
        ledger.currency = textOr(currency, "CNY");
        ledger.isDefault = isDefault;
        ledger.status = 1;
        ledger.createdAt = OffsetDateTime.now().toString();
        ledger.updatedAt = ledger.createdAt;
        return ledger;
    }

    private LedgerMember newMember(long ledgerId, MemberProfile profile, String role) {
        LedgerMember member = new LedgerMember();
        member.ledgerId = ledgerId;
        member.userId = profile.userId();
        member.nickname = profile.nickname();
        member.avatar = profile.avatar();
        member.role = role;
        member.joinedAt = OffsetDateTime.now().toString();
        return member;
    }

    private long generatedId(KeyHolder keyHolder, String entity) {
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Database did not return a generated " + entity + " id");
        return key.longValue();
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private void setLongOrNull(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value);
    }

    private BigDecimal money(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private String moneyText(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).stripTrailingZeros().toPlainString();
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
