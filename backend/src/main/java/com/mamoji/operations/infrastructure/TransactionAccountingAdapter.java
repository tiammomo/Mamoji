package com.mamoji.operations.infrastructure;

import com.mamoji.domain.Models.Account;
import com.mamoji.domain.Models.Category;
import com.mamoji.domain.Models.Ledger;
import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.operations.application.CategoryRepository;
import com.mamoji.operations.application.TransactionAccountingGateway;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Adapter coordinating transaction writes with finance and operations-owned persistence. */
@Repository
public class TransactionAccountingAdapter implements TransactionAccountingGateway {
    private final FinanceRepository financeRepository;
    private final CategoryRepository categoryRepository;

    public TransactionAccountingAdapter(
        FinanceRepository financeRepository,
        CategoryRepository categoryRepository
    ) {
        this.financeRepository = financeRepository;
        this.categoryRepository = categoryRepository;
    }

    @Override
    public Optional<Account> findAccountForUpdate(long id) {
        return financeRepository.findAccountForUpdate(id);
    }

    @Override
    public Optional<Category> findCategoryForUpdate(long id) {
        return categoryRepository.findForUpdate(id);
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
