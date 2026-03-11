package com.mamoji.service;

import com.mamoji.common.exception.ForbiddenOperationException;
import com.mamoji.common.exception.ResourceNotFoundException;
import com.mamoji.common.status.EntityStatus;
import com.mamoji.dto.AccountDTO;
import com.mamoji.entity.Account;
import com.mamoji.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Account domain service.
 *
 * <p>Handles account CRUD, ownership checks, soft delete, and balance summary calculations.
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    /**
     * Returns active accounts owned by user.
     */
    public List<AccountDTO> getAccounts(Long userId) {
        return accountRepository.findByUserIdAndStatus(userId, EntityStatus.ACTIVE)
            .stream()
            .map(this::toDto)
            .toList();
    }

    /**
     * Returns one account after ownership validation.
     */
    public AccountDTO getAccount(Long id, Long userId) {
        return toDto(findOwnedAccount(id, userId));
    }

    /**
     * Creates a new active account for user.
     */
    @Transactional
    public AccountDTO createAccount(AccountDTO dto, Long userId) {
        Account account = new Account();
        account.setName(dto.getName());
        account.setType(dto.getType());
        account.setSubType(dto.getSubType());
        account.setBank(dto.getBank());
        account.setBalance(dto.getBalance() != null ? dto.getBalance() : BigDecimal.ZERO);
        account.setIncludeInNetWorth(dto.getIncludeInNetWorth() != null ? dto.getIncludeInNetWorth() : true);
        account.setUserId(userId);
        account.setLedgerId(dto.getLedgerId());
        account.setStatus(EntityStatus.ACTIVE);
        return toDto(accountRepository.save(account));
    }

    /**
     * Updates mutable account fields.
     */
    @Transactional
    public AccountDTO updateAccount(Long id, AccountDTO dto, Long userId) {
        Account account = findOwnedAccount(id, userId);

        if (dto.getName() != null) {
            account.setName(dto.getName());
        }
        if (dto.getType() != null) {
            account.setType(dto.getType());
        }
        if (dto.getSubType() != null) {
            account.setSubType(dto.getSubType());
        }
        if (dto.getBank() != null) {
            account.setBank(dto.getBank());
        }
        if (dto.getBalance() != null) {
            account.setBalance(dto.getBalance());
        }
        if (dto.getIncludeInNetWorth() != null) {
            account.setIncludeInNetWorth(dto.getIncludeInNetWorth());
        }

        return toDto(accountRepository.save(account));
    }

    /**
     * Soft deletes account by marking status inactive.
     */
    @Transactional
    public void deleteAccount(Long id, Long userId) {
        Account account = findOwnedAccount(id, userId);
        account.setStatus(EntityStatus.INACTIVE);
        accountRepository.save(account);
    }

    /**
     * Returns total assets, liabilities and net worth for user.
     */
    public Map<String, BigDecimal> getAccountSummary(Long userId) {
        BigDecimal totalAssets = defaultAmount(accountRepository.getTotalAssets(userId));
        BigDecimal totalLiabilities = defaultAmount(accountRepository.getTotalLiabilities(userId));

        return Map.of(
            "totalAssets", totalAssets,
            "totalLiabilities", totalLiabilities,
            "netWorth", totalAssets.subtract(totalLiabilities)
        );
    }

    /**
     * Returns account summary for a date range.
     *
     * <p>Current implementation reuses overall summary because account balances are
     * point-in-time values and not transaction snapshots.
     */
    public Map<String, BigDecimal> getAccountSummaryByDateRange(Long userId, LocalDate startDate, LocalDate endDate) {
        return getAccountSummary(userId);
    }

    /**
     * Loads account and validates it belongs to user.
     */
    private Account findOwnedAccount(Long id, Long userId) {
        Account account = accountRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found."));
        if (!account.getUserId().equals(userId)) {
            throw new ForbiddenOperationException("You do not have permission to access this account.");
        }
        return account;
    }

    /**
     * Converts nullable amount to zero.
     */
    private BigDecimal defaultAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Maps entity to DTO.
     */
    private AccountDTO toDto(Account account) {
        AccountDTO dto = new AccountDTO();
        dto.setId(account.getId());
        dto.setName(account.getName());
        dto.setType(account.getType());
        dto.setSubType(account.getSubType());
        dto.setBank(account.getBank());
        dto.setBalance(account.getBalance());
        dto.setIncludeInNetWorth(account.getIncludeInNetWorth());
        dto.setUserId(account.getUserId());
        dto.setLedgerId(account.getLedgerId());
        dto.setStatus(account.getStatus());
        return dto;
    }
}
