package com.mamoji.controller;

import com.mamoji.common.PermissionConstants;
import com.mamoji.dto.AccountDTO;
import com.mamoji.entity.User;
import com.mamoji.service.AccountService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Test suite for AccountControllerTest.
 */

class AccountControllerTest {

    @Test
    void shouldRejectCreateAccountWhenNoPermission() {
        AccountService accountService = Mockito.mock(AccountService.class);
        AccountController controller = new AccountController(accountService);

        AccountDTO dto = new AccountDTO();
        dto.setName("cash");
        User user = User.builder().id(7L).role(2).permissions(PermissionConstants.PERM_NONE).build();

        ResponseEntity<Map<String, Object>> response = controller.createAccount(dto, user);

        Assertions.assertEquals(403, response.getStatusCode().value());
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(1003, response.getBody().get("code"));
        Mockito.verifyNoInteractions(accountService);
    }

    @Test
    void shouldCreateAccountWhenPermissionGranted() {
        AccountService accountService = Mockito.mock(AccountService.class);
        AccountController controller = new AccountController(accountService);

        AccountDTO request = new AccountDTO();
        request.setName("wallet");
        request.setType("cash");

        AccountDTO created = new AccountDTO();
        created.setId(10L);
        created.setName("wallet");
        created.setType("cash");
        created.setBalance(BigDecimal.TEN);
        Mockito.when(accountService.createAccount(request, 7L)).thenReturn(created);

        User user = User.builder()
            .id(7L)
            .role(2)
            .permissions(PermissionConstants.PERM_MANAGE_ACCOUNTS)
            .build();

        ResponseEntity<Map<String, Object>> response = controller.createAccount(request, user);

        Assertions.assertEquals(200, response.getStatusCode().value());
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(0, response.getBody().get("code"));
        Assertions.assertEquals("success", response.getBody().get("message"));
        Assertions.assertEquals(created, response.getBody().get("data"));
        Mockito.verify(accountService).createAccount(request, 7L);
    }

    @Test
    void shouldRouteSummaryByDateRangeWhenDatesProvided() {
        AccountService accountService = Mockito.mock(AccountService.class);
        AccountController controller = new AccountController(accountService);

        Map<String, BigDecimal> summary = Map.of(
            "totalAssets", BigDecimal.valueOf(100),
            "totalLiabilities", BigDecimal.valueOf(20),
            "netWorth", BigDecimal.valueOf(80)
        );
        Mockito.when(accountService.getAccountSummaryByDateRange(7L, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31")))
            .thenReturn(summary);

        User user = User.builder().id(7L).role(2).permissions(PermissionConstants.PERM_NONE).build();
        ResponseEntity<Map<String, Object>> response = controller.getSummary(user, "2026-03-01", "2026-03-31");

        Assertions.assertEquals(200, response.getStatusCode().value());
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(summary, response.getBody().get("data"));
        Mockito.verify(accountService).getAccountSummaryByDateRange(7L, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"));
        Mockito.verify(accountService, Mockito.never()).getAccountSummary(Mockito.anyLong());
    }

    @Test
    void shouldReturnAccountsForAuthenticatedUser() {
        AccountService accountService = Mockito.mock(AccountService.class);
        AccountController controller = new AccountController(accountService);

        AccountDTO account = new AccountDTO();
        account.setId(1L);
        account.setName("cash");
        Mockito.when(accountService.getAccounts(7L)).thenReturn(List.of(account));

        User user = User.builder().id(7L).role(2).permissions(PermissionConstants.PERM_NONE).build();
        ResponseEntity<Map<String, Object>> response = controller.getAccounts(user);

        Assertions.assertEquals(200, response.getStatusCode().value());
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(0, response.getBody().get("code"));
        Assertions.assertEquals(List.of(account), response.getBody().get("data"));
        Mockito.verify(accountService).getAccounts(7L);
    }
}



