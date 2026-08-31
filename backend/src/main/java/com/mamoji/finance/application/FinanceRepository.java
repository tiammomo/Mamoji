package com.mamoji.finance.application;

import com.mamoji.domain.Models.Account;
import com.mamoji.domain.Models.Ledger;
import com.mamoji.domain.Models.LedgerMember;
import com.mamoji.finance.domain.AccountReconciliation;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Persistence port for finance-owned accounts, ledgers, and ledger membership. */
public interface FinanceRepository {
    List<Account> findAccounts(long userId, long companyId);

    List<Account> findAccountsWithMetrics(
        long userId,
        long companyId,
        LocalDate periodStart,
        LocalDate periodEndExclusive
    );

    Optional<Account> findAccountWithMetrics(
        long id,
        LocalDate periodStart,
        LocalDate periodEndExclusive
    );

    Optional<Account> findAccount(long id);

    Optional<Account> findAccountForUpdate(long id);

    Account insertAccount(Account account);

    void updateAccount(Account account);

    boolean accountHasTransactions(long accountId);

    void deleteAccount(Account account);

    List<AccountReconciliation> findAccountReconciliations(
        long accountId,
        long companyId,
        long userId,
        int limit
    );

    AccountReconciliation insertAccountReconciliation(AccountReconciliation reconciliation);

    Optional<Ledger> findLedger(long id);

    Optional<Ledger> findLedgerForUpdate(long id);

    List<Ledger> findOwnedLedgers(long ownerId, long companyId);

    List<Ledger> findAccessibleLedgers(long userId, long companyId);

    Ledger insertLedger(Ledger ledger);

    Ledger ensureAccountingLedger(long ownerId, long companyId, String currency, String subjectName);

    List<LedgerMember> findLedgerMembers(long ledgerId);

    boolean ledgerMemberExists(long ledgerId, long userId);

    Optional<MemberProfile> findMemberProfile(long userId);

    LedgerMember insertLedgerMember(LedgerMember member);

    void deleteLedgerMember(long ledgerId, long userId);

    record MemberProfile(long userId, String nickname, String avatar) {
    }
}
