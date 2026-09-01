package com.mamoji.operations.application;

import com.mamoji.finance.domain.Account;
import com.mamoji.finance.domain.Ledger;
import com.mamoji.operations.domain.Category;
import java.util.List;
import java.util.Optional;

/** Accounting collaboration port required by transaction write use cases. */
public interface TransactionAccountingGateway {
    Optional<Account> findAccountForUpdate(long id);

    Optional<Category> findCategoryForUpdate(long id);

    Optional<Ledger> findLedger(long id);

    List<Ledger> findLedgers(long userId, long companyId);

    boolean ledgerMemberExists(long ledgerId, long userId);

    Ledger ensureCompanyAccountingWorkspace(long ownerId, long companyId, String currency, String subjectName);

    void updateAccount(Account account);
}
