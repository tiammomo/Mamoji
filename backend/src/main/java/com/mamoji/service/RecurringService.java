package com.mamoji.service;

import com.mamoji.domain.Models.Company;
import com.mamoji.finance.application.FinanceRepository;
import com.mamoji.finance.domain.Account;
import com.mamoji.operations.application.CategoryRepository;
import com.mamoji.operations.application.TransactionApplicationService;
import com.mamoji.operations.domain.CreateTransactionCommand;
import com.mamoji.platform.identity.User;
import com.mamoji.recurring.api.RecurringCreateRequest;
import com.mamoji.recurring.api.RecurringUpdateRequest;
import com.mamoji.recurring.application.RecurringItemRepository;
import com.mamoji.recurring.domain.RecurringItem;
import com.mamoji.recurring.domain.RecurringSchedule;
import com.mamoji.service.support.AccessControlService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecurringService {
    private final RecurringItemRepository recurringItems;
    private final FinanceRepository financeRepository;
    private final CategoryRepository categoryRepository;
    private final AccessControlService accessControl;
    private final TransactionApplicationService transactionApplicationService;

    public RecurringService(
        RecurringItemRepository recurringItems,
        FinanceRepository financeRepository,
        CategoryRepository categoryRepository,
        AccessControlService accessControl,
        TransactionApplicationService transactionApplicationService
    ) {
        this.recurringItems = recurringItems;
        this.financeRepository = financeRepository;
        this.categoryRepository = categoryRepository;
        this.accessControl = accessControl;
        this.transactionApplicationService = transactionApplicationService;
    }

    public List<RecurringItem> listRecurring(String authorization) {
        return listRecurring(authorization, null);
    }

    public List<RecurringItem> listRecurring(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        return recurringItems.findByOwnerAndCompany(user.id, company.id);
    }

    @Transactional
    public RecurringItem createRecurring(String authorization, RecurringCreateRequest command) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, command.companyId());
        RecurringItem item = new RecurringItem();
        item.id = UUID.randomUUID().toString();
        item.userId = user.id;
        item.companyId = company.id;
        item.name = command.name();
        item.type = command.type();
        item.amount = command.amount();
        item.frequency = command.frequency();
        item.interval = command.interval();
        item.dayOfWeek = command.dayOfWeek();
        item.dayOfMonth = command.dayOfMonth();
        item.monthOfYear = command.monthOfYear();
        item.startDate = command.startDate().toString();
        item.endDate = command.endDate() == null ? null : command.endDate().toString();
        item.note = blankToNull(command.note());
        validateRecurring(item);
        item.status = 1;
        item.executionCount = 0;
        item.nextExecution = nextExecution(item);
        return recurringItems.insert(item);
    }

    @Transactional
    public RecurringItem updateRecurring(
        String authorization,
        String id,
        Long queryCompanyId,
        RecurringUpdateRequest command
    ) {
        RecurringItem item = copyRecurring(requireRecurringForUpdate(
            authorization,
            id,
            queryCompanyId == null ? command.companyId() : queryCompanyId
        ));
        applyUpdate(item, command);
        validateRecurring(item);
        item.nextExecution = nextExecution(item);
        recurringItems.update(item);
        return item;
    }

    @Transactional
    public void deleteRecurring(String authorization, String id) {
        deleteRecurring(authorization, id, null);
    }

    @Transactional
    public void deleteRecurring(String authorization, String id, Long companyId) {
        RecurringItem item = requireRecurringForUpdate(authorization, id, companyId);
        recurringItems.delete(item.id);
    }

    @Transactional
    public Map<String, Object> toggleRecurring(String authorization, String id) {
        return toggleRecurring(authorization, id, null);
    }

    @Transactional
    public Map<String, Object> toggleRecurring(String authorization, String id, Long companyId) {
        RecurringItem item = copyRecurring(requireRecurringForUpdate(authorization, id, companyId));
        item.status = item.status == 1 ? 0 : 1;
        recurringItems.update(item);
        return Map.of("success", true, "status", item.status);
    }

    @Transactional
    public Map<String, Object> executeRecurring(String authorization, String id) {
        return executeRecurring(authorization, id, null);
    }

    @Transactional
    public Map<String, Object> executeRecurring(String authorization, String id, Long companyId) {
        RecurringItem item = copyRecurring(requireRecurringForUpdate(authorization, id, companyId));
        User user = accessControl.requireUser(authorization);
        if (item.status != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Recurring item is disabled");
        }
        if (item.endDate != null && LocalDate.now().isAfter(LocalDate.parse(item.endDate))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Recurring item has ended");
        }
        if (LocalDate.now().toString().equals(item.lastExecuted)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Recurring item has already been executed today");
        }
        long userId = user.id;
        List<Account> accounts = financeRepository.findAccounts(user.id, item.companyId);
        if (accounts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Create an account before executing a recurring item");
        }
        long categoryId = defaultCategoryId(userId, item.companyId, item.type)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Create a matching category before executing a recurring item"));
        CreateTransactionCommand command = new CreateTransactionCommand(
            item.companyId,
            item.type,
            item.amount,
            categoryId,
            accounts.getFirst().id,
            LocalDate.now(),
            item.note == null ? item.name : item.note,
            null
        );
        Map<String, Object> result = transactionApplicationService.create(authorization, command);
        item.lastExecuted = LocalDate.now().toString();
        item.executionCount++;
        item.nextExecution = nextExecution(item);
        recurringItems.update(item);
        return result;
    }

    private RecurringItem requireRecurringForUpdate(String authorization, String id, Long companyId) {
        User user = accessControl.requireUser(authorization);
        RecurringItem item = recurringItems.findByIdForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurring item not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? item.companyId : companyId);
        if (item.userId != user.id || !Objects.equals(item.companyId, company.id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        return item;
    }

    private void applyUpdate(RecurringItem item, RecurringUpdateRequest command) {
        if (command.name() != null) item.name = command.name();
        if (command.type() != null) item.type = command.type();
        if (command.amount() != null) item.amount = command.amount();
        if (command.frequency() != null) item.frequency = command.frequency();
        if (command.interval() != null) item.interval = command.interval();
        if (command.dayOfWeek() != null) item.dayOfWeek = command.dayOfWeek();
        if (command.dayOfMonth() != null) item.dayOfMonth = command.dayOfMonth();
        if (command.monthOfYear() != null) item.monthOfYear = command.monthOfYear();
        if (command.startDate() != null) item.startDate = command.startDate().toString();
        if (command.endDate() != null) item.endDate = command.endDate().toString();
        if (command.note() != null) item.note = blankToNull(command.note());
    }

    private void validateRecurring(RecurringItem item) {
        if (item.interval < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "interval must be greater than 0");
        }
        LocalDate start = LocalDate.parse(item.startDate);
        if (item.endDate == null) {
            return;
        }
        LocalDate end = LocalDate.parse(item.endDate);
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endDate must be on or after startDate");
        }
    }

    private String nextExecution(RecurringItem item) {
        return RecurringSchedule.next(item).toString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Optional<Long> defaultCategoryId(long userId, long companyId, int type) {
        String typeName = type == 1 ? "income" : "expense";
        return categoryRepository.findAll(userId, companyId, typeName).stream()
            .map(category -> category.id)
            .min(Long::compareTo);
    }

    private RecurringItem copyRecurring(RecurringItem source) {
        RecurringItem copy = new RecurringItem();
        try {
            for (var field : RecurringItem.class.getFields()) {
                field.set(copy, field.get(source));
            }
            return copy;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to copy recurring item", ex);
        }
    }
}
