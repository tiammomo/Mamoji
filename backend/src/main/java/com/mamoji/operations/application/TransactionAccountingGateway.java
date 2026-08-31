package com.mamoji.operations.application;

import com.mamoji.domain.Models.Account;
import com.mamoji.domain.Models.Category;
import com.mamoji.domain.Models.Ledger;
import java.util.List;
import java.util.Optional;

/** Accounting collaboration port required by transaction write use cases. */
public interface TransactionAccountingGateway {
    Optional<Account> findAccountForUpdate(long id);

    Optional<Category> findCategoryForUpdate(long id);

    Optional<Ledger> findLedger(long id);

    List<Ledger> findLedgers(long userId, long companyId);

    Ledger ensureCompanyAccountingWorkspace(long ownerId, long companyId, String currency, String subjectName);

    void updateAccount(Account account);
}
