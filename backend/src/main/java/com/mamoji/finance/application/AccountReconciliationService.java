package com.mamoji.finance.application;

import static com.mamoji.common.PayloadReader.nullableText;
import static com.mamoji.common.PayloadReader.number;
import static com.mamoji.common.PayloadReader.textOr;

import com.mamoji.domain.Models.User;
import com.mamoji.finance.domain.Account;
import com.mamoji.finance.domain.AccountReconciliation;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Finance application boundary for immutable account reconciliation snapshots. */
@Service
public class AccountReconciliationService {
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final FinanceRepository repository;
    private final AccessControlService accessControl;
    private final AccountApplicationService accountingService;

    public AccountReconciliationService(
        FinanceRepository repository,
        AccessControlService accessControl,
        AccountApplicationService accountingService
    ) {
        this.repository = repository;
        this.accessControl = accessControl;
        this.accountingService = accountingService;
    }

    public List<AccountReconciliation> list(String authorization, long accountId, Long companyId) {
        Account account = accountingService.getAccount(authorization, accountId, companyId);
        return repository.findAccountReconciliations(account.id, account.companyId, account.userId, 50);
    }

    @Transactional
    public AccountReconciliation create(
        String authorization,
        long accountId,
        Long companyId,
        Map<String, Object> body
    ) {
        User operator = accessControl.requireUser(authorization);
        Account account = accountingService.getAccountForUpdate(authorization, accountId, companyId);
        String statementDate = validDate(textOr(body.get("statementDate"), LocalDate.now().toString()));
        BigDecimal statementBalance = number(body.get("statementBalance"), account.balance)
            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal systemBalance = account.balance.setScale(2, RoundingMode.HALF_UP);
        BigDecimal difference = statementBalance.subtract(systemBalance).setScale(2, RoundingMode.HALF_UP);
        String status = difference.abs().compareTo(TOLERANCE) <= 0 ? "reconciled" : "exception";
        String now = OffsetDateTime.now().toString();
        AccountReconciliation record = repository.insertAccountReconciliation(new AccountReconciliation(
            0,
            account.companyId,
            account.userId,
            account.id,
            statementDate,
            statementBalance,
            systemBalance,
            difference,
            status,
            nullableText(body.get("note")),
            operator.id,
            now
        ));

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("reconciliationStatus", status);
        if ("reconciled".equals(status)) update.put("lastReconciledAt", statementDate);
        accountingService.updateAccount(authorization, account.id, account.companyId, update);
        return record;
    }

    private String validDate(String value) {
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.isAfter(LocalDate.now())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "statementDate must not be in the future");
            }
            return date.toString();
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "statementDate must use yyyy-MM-dd format");
        }
    }

}
