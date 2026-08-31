package com.mamoji.operations.infrastructure;

import com.mamoji.domain.Models.Account;
import com.mamoji.domain.Models.Category;
import com.mamoji.domain.Models.Ledger;
import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.operations.application.TransactionAccountingGateway;
import com.mamoji.repository.InMemoryStore;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Finance-backed transaction collaboration adapter; category locking remains transitional. */
@Repository
public class LegacyTransactionAccountingGateway implements TransactionAccountingGateway {
    private final FinanceRepository financeRepository;
    private final InMemoryStore store;

    public LegacyTransactionAccountingGateway(FinanceRepository financeRepository, InMemoryStore store) {
        this.financeRepository = financeRepository;
        this.store = store;
    }

    @Override
    public Optional<Account> findAccountForUpdate(long id) {
        return financeRepository.findAccountForUpdate(id);
    }

    @Override
    public Optional<Category> findCategoryForUpdate(long id) {
        return store.categoryForUpdate(id);
    }

    @Override
    public Optional<Ledger> findLedger(long id) {
        return financeRepository.findLedger(id);
    }

    @Override
    public List<Ledger> findLedgers(long userId, long companyId) {
        return financeRepository.findOwnedLedgers(userId, companyId);
    }

    @Override
    public Ledger ensureCompanyAccountingWorkspace(
        long ownerId,
        long companyId,
        String currency,
        String subjectName
    ) {
        return financeRepository.ensureAccountingLedger(ownerId, companyId, currency, subjectName);
    }

    @Override
    public void updateAccount(Account account) {
        financeRepository.updateAccount(account);
    }
}
