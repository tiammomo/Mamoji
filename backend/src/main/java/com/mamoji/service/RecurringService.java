package com.mamoji.service;

import com.mamoji.domain.Models.RecurringItem;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.intValue;
import static com.mamoji.common.PayloadReader.nullableText;
import static com.mamoji.common.PayloadReader.number;
import static com.mamoji.common.PayloadReader.optionalInt;
import static com.mamoji.common.PayloadReader.text;
import static com.mamoji.service.support.DomainSupport.assertOwner;
import static com.mamoji.service.support.DomainSupport.require;

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
        long userId = accessControl.requireUser(authorization).id;
        return store.recurringItems.values().stream()
            .filter(item -> item.userId == userId)
            .sorted(Comparator.comparing(item -> item.nextExecution))
            .toList();
    }

    public RecurringItem createRecurring(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        RecurringItem item = new RecurringItem();
        item.id = UUID.randomUUID().toString();
        item.userId = user.id;
        applyRecurring(item, body);
        item.status = 1;
        item.executionCount = 0;
        item.nextExecution = nextExecution(item);
        store.saveRecurring(item);
        return item;
    }

    public RecurringItem updateRecurring(String authorization, String id, Map<String, Object> body) {
        RecurringItem item = requireRecurring(authorization, id);
        applyRecurring(item, body);
        item.nextExecution = nextExecution(item);
        store.saveRecurring(item);
        return item;
    }

    public void deleteRecurring(String authorization, String id) {
        RecurringItem item = requireRecurring(authorization, id);
        store.deleteRecurring(item.id);
    }

    public Map<String, Object> toggleRecurring(String authorization, String id) {
        RecurringItem item = requireRecurring(authorization, id);
        item.status = item.status == 1 ? 0 : 1;
        store.saveRecurring(item);
        return Map.of("success", true, "status", item.status);
    }

    public Map<String, Object> executeRecurring(String authorization, String id) {
        RecurringItem item = requireRecurring(authorization, id);
        long userId = accessControl.requireUser(authorization).id;
        Map<String, Object> body = new HashMap<>();
        body.put("type", item.type);
        body.put("amount", item.amount);
        body.put("categoryId", defaultCategoryId(userId, item.type).orElse(1L));
        body.put("accountId", accountingService.listAccounts(authorization).get(0).id);
        body.put("date", LocalDate.now().toString());
        body.put("note", item.note == null ? item.name : item.note);
        Map<String, Object> result = accountingService.createTransaction(authorization, body);
        item.lastExecuted = LocalDate.now().toString();
        item.executionCount++;
        item.nextExecution = nextExecution(item);
        store.saveRecurring(item);
        return result;
    }

    private RecurringItem requireRecurring(String authorization, String id) {
        long userId = accessControl.requireUser(authorization).id;
        RecurringItem item = require(store.recurringItems.get(id), "Recurring item not found");
        assertOwner(item.userId, userId);
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

    private Optional<Long> defaultCategoryId(long userId, int type) {
        String typeName = type == 1 ? "income" : "expense";
        return store.categories.values().stream()
            .filter(category -> category.userId == userId && category.type.equals(typeName))
            .map(category -> category.id)
            .findFirst();
    }
}
