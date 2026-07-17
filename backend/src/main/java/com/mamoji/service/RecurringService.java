package com.mamoji.service;

import com.mamoji.domain.Models.RecurringItem;
import com.mamoji.domain.Models.User;
import com.mamoji.domain.Models.Company;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.intValue;
import static com.mamoji.common.PayloadReader.nullableText;
import static com.mamoji.common.PayloadReader.number;
import static com.mamoji.common.PayloadReader.optionalInt;
import static com.mamoji.common.PayloadReader.optionalLong;
import static com.mamoji.common.PayloadReader.text;

@Service
public class RecurringService {
    private final InMemoryStore store;
    private final AccessControlService accessControl;
    private final AccountingService accountingService;

    public RecurringService(InMemoryStore store, AccessControlService accessControl, AccountingService accountingService) {
        this.store = store;
        this.accessControl = accessControl;
        this.accountingService = accountingService;
    }

    public List<RecurringItem> listRecurring(String authorization) {
        return listRecurring(authorization, null);
    }

    public List<RecurringItem> listRecurring(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        return store.queryRecurring(user.id, company.id);
    }

    @Transactional
    public RecurringItem createRecurring(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, optionalLong(body.get("companyId")).orElse(null));
        RecurringItem item = new RecurringItem();
        item.id = UUID.randomUUID().toString();
        item.userId = user.id;
        item.companyId = company.id;
        applyRecurring(item, body);
        item.status = 1;
        item.executionCount = 0;
        item.nextExecution = nextExecution(item);
        store.saveRecurring(item);
        return item;
    }

    @Transactional
    public RecurringItem updateRecurring(String authorization, String id, Map<String, Object> body) {
        RecurringItem item = copyRecurring(requireRecurringForUpdate(
            authorization,
            id,
            optionalLong(body.get("companyId")).orElse(null)
        ));
        applyRecurring(item, body);
        item.nextExecution = nextExecution(item);
        store.saveRecurring(item);
        return item;
    }

    @Transactional
    public void deleteRecurring(String authorization, String id) {
        deleteRecurring(authorization, id, null);
    }

    @Transactional
    public void deleteRecurring(String authorization, String id, Long companyId) {
        RecurringItem item = requireRecurringForUpdate(authorization, id, companyId);
        store.deleteRecurring(item.id);
    }

    @Transactional
    public Map<String, Object> toggleRecurring(String authorization, String id) {
        return toggleRecurring(authorization, id, null);
    }

    @Transactional
    public Map<String, Object> toggleRecurring(String authorization, String id, Long companyId) {
        RecurringItem item = copyRecurring(requireRecurringForUpdate(authorization, id, companyId));
        item.status = item.status == 1 ? 0 : 1;
        store.saveRecurring(item);
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
        List<com.mamoji.domain.Models.Account> accounts = accountingService.listAccounts(authorization, item.companyId);
        if (accounts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Create an account before executing a recurring item");
        }
        long categoryId = defaultCategoryId(userId, item.companyId, item.type)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Create a matching category before executing a recurring item"));
        Map<String, Object> body = new HashMap<>();
        body.put("companyId", item.companyId);
        body.put("type", item.type);
        body.put("amount", item.amount);
        body.put("categoryId", categoryId);
        body.put("accountId", accounts.get(0).id);
        body.put("date", LocalDate.now().toString());
        body.put("note", item.note == null ? item.name : item.note);
        Map<String, Object> result = accountingService.createTransaction(authorization, body);
        item.lastExecuted = LocalDate.now().toString();
        item.executionCount++;
        item.nextExecution = nextExecution(item);
        store.saveRecurring(item);
        return result;
    }

    private RecurringItem requireRecurringForUpdate(String authorization, String id, Long companyId) {
        User user = accessControl.requireUser(authorization);
        RecurringItem item = store.recurringForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurring item not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? item.companyId : companyId);
        if (item.userId != user.id || !Objects.equals(item.companyId, company.id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        return item;
    }

    private void applyRecurring(RecurringItem item, Map<String, Object> body) {
        if (body.containsKey("name")) {
            item.name = text(body.get("name"));
        }
        if (body.containsKey("type")) {
            item.type = intValue(body.get("type"), item.type == 0 ? 2 : item.type);
        }
        if (body.containsKey("amount")) {
            item.amount = number(body.get("amount"), item.amount == null ? BigDecimal.ZERO : item.amount);
        }
        if (body.containsKey("frequency")) {
            item.frequency = text(body.get("frequency"));
        }
        if (body.containsKey("interval")) {
            item.interval = intValue(body.get("interval"), item.interval == 0 ? 1 : item.interval);
        }
        if (body.containsKey("dayOfWeek")) {
            item.dayOfWeek = optionalInt(body.get("dayOfWeek")).orElse(null);
        }
        if (body.containsKey("dayOfMonth")) {
            item.dayOfMonth = optionalInt(body.get("dayOfMonth")).orElse(null);
        }
        if (body.containsKey("monthOfYear")) {
            item.monthOfYear = optionalInt(body.get("monthOfYear")).orElse(null);
        }
        if (body.containsKey("startDate")) {
            item.startDate = text(body.get("startDate"));
        }
        if (body.containsKey("endDate")) {
            item.endDate = nullableText(body.get("endDate"));
        }
        if (body.containsKey("note")) {
            item.note = nullableText(body.get("note"));
        }
        if (item.name == null) {
            item.name = "周期项目";
        }
        if (item.frequency == null) {
            item.frequency = "monthly";
        }
        if (item.interval == 0) {
            item.interval = 1;
        }
        if (item.amount == null) {
            item.amount = BigDecimal.ZERO;
        }
        if (item.startDate == null) {
            item.startDate = LocalDate.now().toString();
        }
        validateRecurringDates(item);
    }

    private void validateRecurringDates(RecurringItem item) {
        if (item.interval < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "interval must be greater than 0");
        }
        LocalDate start = parseRecurringDate("startDate", item.startDate);
        if (item.endDate == null) {
            return;
        }
        LocalDate end = parseRecurringDate("endDate", item.endDate);
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endDate must be on or after startDate");
        }
    }

    private LocalDate parseRecurringDate(String field, String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must use yyyy-MM-dd format");
        }
    }

    private String nextExecution(RecurringItem item) {
        LocalDate base = item.lastExecuted == null ? LocalDate.parse(item.startDate) : LocalDate.parse(item.lastExecuted);
        return switch (item.frequency) {
            case "daily" -> base.plusDays(item.interval).toString();
            case "weekly" -> base.plusWeeks(item.interval).toString();
            case "yearly" -> base.plusYears(item.interval).toString();
            default -> base.plusMonths(item.interval).toString();
        };
    }

    private Optional<Long> defaultCategoryId(long userId, long companyId, int type) {
        String typeName = type == 1 ? "income" : "expense";
        return store.queryCategories(userId, companyId, typeName).stream()
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
