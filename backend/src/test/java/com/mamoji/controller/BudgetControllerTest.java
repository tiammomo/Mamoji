package com.mamoji.controller;

import com.mamoji.common.PermissionConstants;
import com.mamoji.dto.BudgetDTO;
import com.mamoji.entity.User;
import com.mamoji.service.BudgetService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Test suite for BudgetControllerTest.
 */

class BudgetControllerTest {

    @Test
    void shouldRejectCreateBudgetWhenNoPermission() {
        BudgetService budgetService = Mockito.mock(BudgetService.class);
        BudgetController controller = new BudgetController(budgetService);

        BudgetDTO dto = new BudgetDTO();
        dto.setName("monthly");
        User user = User.builder().id(7L).role(2).permissions(PermissionConstants.PERM_NONE).build();

        ResponseEntity<Map<String, Object>> response = controller.createBudget(dto, user);

        Assertions.assertEquals(403, response.getStatusCode().value());
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(1003, response.getBody().get("code"));
        Mockito.verifyNoInteractions(budgetService);
    }

    @Test
    void shouldCreateBudgetWhenPermissionGranted() {
        BudgetService budgetService = Mockito.mock(BudgetService.class);
        BudgetController controller = new BudgetController(budgetService);

        BudgetDTO request = new BudgetDTO();
        request.setName("food");
        request.setAmount(BigDecimal.valueOf(800));
        request.setStartDate(LocalDate.parse("2026-03-01"));
        request.setEndDate(LocalDate.parse("2026-03-31"));

        BudgetDTO created = new BudgetDTO();
        created.setId(88L);
        created.setName("food");
        created.setAmount(BigDecimal.valueOf(800));
        Mockito.when(budgetService.createBudget(request, 7L)).thenReturn(created);

        User user = User.builder()
            .id(7L)
            .role(2)
            .permissions(PermissionConstants.PERM_MANAGE_BUDGETS)
            .build();

        ResponseEntity<Map<String, Object>> response = controller.createBudget(request, user);

        Assertions.assertEquals(200, response.getStatusCode().value());
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(0, response.getBody().get("code"));
        Assertions.assertEquals(created, response.getBody().get("data"));
        Mockito.verify(budgetService).createBudget(request, 7L);
    }

    @Test
    void shouldRouteBudgetQueryToDateRangeMethod() {
        BudgetService budgetService = Mockito.mock(BudgetService.class);
        BudgetController controller = new BudgetController(budgetService);

        BudgetDTO dto = new BudgetDTO();
        dto.setId(1L);
        dto.setName("month");
        Mockito.when(budgetService.getBudgetsByDateRange(7L, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31")))
            .thenReturn(List.of(dto));

        User user = User.builder().id(7L).role(2).permissions(PermissionConstants.PERM_NONE).build();
        ResponseEntity<Map<String, Object>> response = controller.getBudgets(user, "2026-03-01", "2026-03-31");

        Assertions.assertEquals(200, response.getStatusCode().value());
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(List.of(dto), response.getBody().get("data"));
        Mockito.verify(budgetService).getBudgetsByDateRange(7L, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"));
        Mockito.verify(budgetService, Mockito.never()).getBudgets(Mockito.anyLong());
    }

    @Test
    void shouldReturnAllBudgetsWithoutDateRange() {
        BudgetService budgetService = Mockito.mock(BudgetService.class);
        BudgetController controller = new BudgetController(budgetService);

        BudgetDTO dto = new BudgetDTO();
        dto.setId(1L);
        dto.setName("all");
        Mockito.when(budgetService.getBudgets(7L)).thenReturn(List.of(dto));

        User user = User.builder().id(7L).role(2).permissions(PermissionConstants.PERM_NONE).build();
        ResponseEntity<Map<String, Object>> response = controller.getBudgets(user, null, null);

        Assertions.assertEquals(200, response.getStatusCode().value());
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(List.of(dto), response.getBody().get("data"));
        Mockito.verify(budgetService).getBudgets(7L);
    }
}



