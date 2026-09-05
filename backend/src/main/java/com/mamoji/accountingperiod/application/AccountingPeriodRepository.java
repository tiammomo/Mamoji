package com.mamoji.accountingperiod.application;

import com.mamoji.accountingperiod.domain.AccountingPeriodControl;
import java.util.Optional;

/** Persistence port for the company accounting close watermark. */
public interface AccountingPeriodRepository {
    Optional<AccountingPeriodControl> findByCompany(long companyId);

    Optional<AccountingPeriodControl> findByCompanyForUpdate(long companyId);

    AccountingPeriodControl update(AccountingPeriodControl control);
}
