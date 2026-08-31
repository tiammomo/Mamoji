package com.mamoji.operations.infrastructure;

import com.mamoji.domain.Models.Account;
import com.mamoji.domain.Models.Category;
import com.mamoji.domain.Models.Ledger;
import com.mamoji.operations.application.TransactionAccountingGateway;
import com.mamoji.repository.InMemoryStore;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Transitional adapter over the legacy accounting store.
 *
 * <p>The operations application layer depends only on its own collaboration port;
 * account and ledger persistence can move behind this adapter independently.</p>
 */
@Repository
public class LegacyTransactionAccountingGateway implements TransactionAccountingGateway {
    private final InMemoryStore store;

    public LegacyTransactionAccountingGateway(InMemoryStore store) {
        this.store = store;
    }

    @Override
    public Optional<Account> findAccountForUpdate(long id) {
        return store.accountForUpdate(id);
    }

    @Override
    public Optional<Category> findCategoryForUpdate(long id) {
        return store.categoryForUpdate(id);
    }

    @Override
    public Optional<Ledger> findLedger(long id) {
        return store.findLedger(id);
    }

    @Override
    public List<Ledger> findLedgers(long userId, long companyId) {
        return store.queryLedgers(userId, companyId);
    }

    @Override
    public Ledger ensureCompanyAccountingWorkspace(
        long ownerId,
        long companyId,
        String currency,
        String subjectName
    ) {
        return store.ensureCompanyAccountingWorkspace(ownerId, companyId, currency, subjectName);
    }

    @Override
    public void updateAccount(Account account) {
        store.saveAccount(account);
    }
}
