package com.mamoji.service;

import com.mamoji.dto.AccountDTO;
import com.mamoji.entity.Account;
import com.mamoji.repository.AccountRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
/**
 * Test suite for AccountServiceTest.
 */
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private AccountService accountService;

    @Test
    void shouldApplyDefaultValuesWhenCreatingAccount() {
        AccountDTO dto = new AccountDTO();
        dto.setName("wallet");
        dto.setType("cash");
        Mockito.when(accountRepository.save(Mockito.any(Account.class)))
            .thenAnswer(invocation -> {
                Account account = invocation.getArgument(0, Account.class);
                account.setId(9L);
                return account;
            });

        AccountDTO created = accountService.createAccount(dto, 7L);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        Mockito.verify(accountRepository).save(captor.capture());
        Account saved = captor.getValue();

        Assertions.assertEquals("wallet", saved.getName());
        Assertions.assertEquals("cash", saved.getType());
        Assertions.assertEquals(BigDecimal.ZERO, saved.getBalance());
        Assertions.assertTrue(saved.getIncludeInNetWorth());
        Assertions.assertEquals(1, saved.getStatus());
        Assertions.assertEquals(7L, saved.getUserId());
        Assertions.assertEquals(9L, created.getId());
    }

    @Test
    void shouldThrowWhenUpdatingAccountOfAnotherUser() {
        Account account = Account.builder()
            .id(10L)
            .name("cash")
            .type("cash")
            .userId(100L)
            .status(1)
            .build();
        Mockito.when(accountRepository.findById(10L)).thenReturn(Optional.of(account));

        RuntimeException exception = Assertions.assertThrows(
            RuntimeException.class,
            () -> accountService.updateAccount(10L, new AccountDTO(), 7L)
        );

        Assertions.assertEquals("You do not have permission to access this account.", exception.getMessage());
        Mockito.verify(accountRepository, Mockito.never()).save(Mockito.any(Account.class));
    }

    @Test
    void shouldSoftDeleteAccount() {
        Account account = Account.builder()
            .id(10L)
            .name("cash")
            .type("cash")
            .userId(7L)
            .status(1)
            .build();
        Mockito.when(accountRepository.findById(10L)).thenReturn(Optional.of(account));

        accountService.deleteAccount(10L, 7L);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        Mockito.verify(accountRepository).save(captor.capture());
        Assertions.assertEquals(0, captor.getValue().getStatus());
    }

    @Test
    void shouldFallbackToZeroWhenSummaryValuesAreNull() {
        Mockito.when(accountRepository.getTotalAssets(7L)).thenReturn(null);
        Mockito.when(accountRepository.getTotalLiabilities(7L)).thenReturn(null);

        Map<String, BigDecimal> summary = accountService.getAccountSummary(7L);

        Assertions.assertEquals(BigDecimal.ZERO, summary.get("totalAssets"));
        Assertions.assertEquals(BigDecimal.ZERO, summary.get("totalLiabilities"));
        Assertions.assertEquals(BigDecimal.ZERO, summary.get("netWorth"));
    }
}

