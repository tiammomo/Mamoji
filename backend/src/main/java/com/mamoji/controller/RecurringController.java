package com.mamoji.controller;

import com.mamoji.common.api.ApiResponses;
import com.mamoji.common.exception.ResourceNotFoundException;
import com.mamoji.entity.User;
import com.mamoji.security.AuthenticationUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory recurring transaction management endpoints.
 *
 * <p>This controller provides a temporary lightweight implementation backed by
 * process memory for CRUD/toggle/manual execute workflows.
 */
@RestController
@RequestMapping("/api/v1/recurring")
public class RecurringController {

    private static final Map<Long, List<RecurringItem>> STORE = new ConcurrentHashMap<>();
    private static final AtomicLong ID_GENERATOR = new AtomicLong(1);

    /**
     * Returns paginated recurring definitions for current user.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
        @AuthenticationUser User user,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);

        List<RecurringItem> all = new ArrayList<>(STORE.getOrDefault(user.getId(), List.of()));
        all.sort(Comparator.comparingLong(RecurringItem::id).reversed());

        int from = Math.min((safePage - 1) * safePageSize, all.size());
        int to = Math.min(from + safePageSize, all.size());
        List<Map<String, Object>> list = all.subList(from, to).stream().map(this::toMap).toList();

        Map<String, Object> payload = new HashMap<>();
        payload.put("total", all.size());
        payload.put("page", safePage);
        payload.put("pageSize", safePageSize);
        payload.put("list", list);
        return ApiResponses.ok(payload);
    }

    /**
     * Returns one recurring definition.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@AuthenticationUser User user, @PathVariable Long id) {
        return ApiResponses.ok(toMap(findRequired(user.getId(), id)));
    }

    /**
     * Creates a recurring definition.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@AuthenticationUser User user, @RequestBody Map<String, Object> body) {
        RecurringItem item = new RecurringItem(
            ID_GENERATOR.getAndIncrement(),
            user.getId(),
            asString(body.get("name"), "未命名定期项"),
            asInt(body.get("type"), 2),
            asDecimal(body.get("amount"), BigDecimal.ZERO),
            asString(body.get("recurrenceType"), "MONTHLY"),
            asInt(body.get("intervalCount"), 1),
            asNullableInt(body.get("dayOfWeek")),
            asNullableInt(body.get("dayOfMonth")),
            asNullableInt(body.get("monthOfYear")),
            asDate(body.get("startDate"), LocalDate.now()),
            asNullableDate(body.get("endDate")),
            null,
            LocalDate.now(),
            1,
            0,
            asString(body.get("remark"), "")
        );
        STORE.computeIfAbsent(user.getId(), key -> new ArrayList<>()).add(item);
        return ApiResponses.ok(toMap(item));
    }

    /**
     * Updates one recurring definition.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
        @AuthenticationUser User user,
        @PathVariable Long id,
        @RequestBody Map<String, Object> body
    ) {
        RecurringItem current = findRequired(user.getId(), id);
        RecurringItem updated = new RecurringItem(
            current.id(),
            current.userId(),
            asString(body.get("name"), current.name()),
            asInt(body.get("type"), current.type()),
            asDecimal(body.get("amount"), current.amount()),
            asString(body.get("recurrenceType"), current.recurrenceType()),
            asInt(body.get("intervalCount"), current.intervalCount()),
            asNullableInt(body.get("dayOfWeek"), current.dayOfWeek()),
            asNullableInt(body.get("dayOfMonth"), current.dayOfMonth()),
            asNullableInt(body.get("monthOfYear"), current.monthOfYear()),
            asDate(body.get("startDate"), current.startDate()),
            asNullableDate(body.get("endDate"), current.endDate()),
            current.lastExecutionDate(),
            current.nextExecutionDate(),
            current.status(),
            current.executionCount(),
            asString(body.get("remark"), current.remark())
        );
        replace(user.getId(), updated);
        return ApiResponses.ok(toMap(updated));
    }

    /**
     * Deletes one recurring definition.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@AuthenticationUser User user, @PathVariable Long id) {
        List<RecurringItem> list = STORE.getOrDefault(user.getId(), new ArrayList<>());
        list.removeIf(item -> item.id().equals(id));
        return ApiResponses.ok(null);
    }

    /**
     * Toggles active status of one recurring definition.
     */
    @PostMapping("/{id}/toggle")
    public ResponseEntity<Map<String, Object>> toggle(@AuthenticationUser User user, @PathVariable Long id) {
        RecurringItem current = findRequired(user.getId(), id);
        RecurringItem toggled = new RecurringItem(
            current.id(), current.userId(), current.name(), current.type(), current.amount(),
            current.recurrenceType(), current.intervalCount(), current.dayOfWeek(), current.dayOfMonth(),
            current.monthOfYear(), current.startDate(), current.endDate(), current.lastExecutionDate(),
            current.nextExecutionDate(), current.status() == 1 ? 0 : 1, current.executionCount(), current.remark()
        );
        replace(user.getId(), toggled);
        return ApiResponses.ok(toMap(toggled));
    }

    /**
     * Executes one recurring definition manually and returns generated transaction payload.
     */
    @PostMapping("/{id}/execute")
    public ResponseEntity<Map<String, Object>> execute(@AuthenticationUser User user, @PathVariable Long id) {
        RecurringItem current = findRequired(user.getId(), id);
        LocalDate now = LocalDate.now();
        RecurringItem executed = new RecurringItem(
            current.id(), current.userId(), current.name(), current.type(), current.amount(),
            current.recurrenceType(), current.intervalCount(), current.dayOfWeek(), current.dayOfMonth(),
            current.monthOfYear(), current.startDate(), current.endDate(), now, computeNextDate(current, now),
            current.status(), current.executionCount() + 1, current.remark()
        );
        replace(user.getId(), executed);

        Map<String, Object> transaction = new HashMap<>();
        transaction.put("id", -executed.id());
        transaction.put("type", executed.type());
        transaction.put("amount", executed.amount());
        transaction.put("date", now.toString());
        transaction.put("remark", "Manual execute from recurring #" + executed.id());
        return ApiResponses.ok(transaction);
    }

    /**
     * Computes next execution date according to recurrence policy.
     */
    private LocalDate computeNextDate(RecurringItem item, LocalDate from) {
        return switch (item.recurrenceType()) {
            case "DAILY" -> from.plusDays(Math.max(1, item.intervalCount()));
            case "WEEKLY" -> from.plusWeeks(Math.max(1, item.intervalCount()));
            case "YEARLY" -> from.plusYears(Math.max(1, item.intervalCount()));
            default -> from.plusMonths(Math.max(1, item.intervalCount()));
        };
    }

    /**
     * Finds a recurring item owned by user, otherwise throws not-found error.
     */
    private RecurringItem findRequired(Long userId, Long id) {
        return STORE.getOrDefault(userId, List.of()).stream()
            .filter(item -> item.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("定期记账不存在。"));
    }

    /**
     * Replaces existing item with same id, or appends when absent.
     */
    private void replace(Long userId, RecurringItem replacement) {
        List<RecurringItem> list = STORE.computeIfAbsent(userId, key -> new ArrayList<>());
        for (int index = 0; index < list.size(); index++) {
            if (list.get(index).id().equals(replacement.id())) {
                list.set(index, replacement);
                return;
            }
        }
        list.add(replacement);
    }

    /**
     * Converts recurring record to API payload map.
     */
    private Map<String, Object> toMap(RecurringItem item) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", item.id());
        map.put("name", item.name());
        map.put("type", item.type());
        map.put("amount", item.amount());
        map.put("recurrenceType", item.recurrenceType());
        map.put("intervalCount", item.intervalCount());
        map.put("dayOfWeek", item.dayOfWeek());
        map.put("dayOfMonth", item.dayOfMonth());
        map.put("monthOfYear", item.monthOfYear());
        map.put("startDate", item.startDate() != null ? item.startDate().toString() : null);
        map.put("endDate", item.endDate() != null ? item.endDate().toString() : null);
        map.put("nextExecutionDate", item.nextExecutionDate() != null ? item.nextExecutionDate().toString() : null);
        map.put("lastExecutionDate", item.lastExecutionDate() != null ? item.lastExecutionDate().toString() : null);
        map.put("status", item.status());
        map.put("executionCount", item.executionCount());
        map.put("remark", item.remark());
        return map;
    }

    /**
     * Parses non-empty string field with default fallback.
     */
    private String asString(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    /**
     * Parses integer with default fallback.
     */
    private int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    /**
     * Parses nullable integer.
     */
    private Integer asNullableInt(Object value) {
        return asNullableInt(value, null);
    }

    /**
     * Parses nullable integer with explicit default.
     */
    private Integer asNullableInt(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    /**
     * Parses decimal amount with default fallback.
     */
    private BigDecimal asDecimal(Object value, BigDecimal defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    /**
     * Parses ISO date with default fallback.
     */
    private LocalDate asDate(Object value, LocalDate defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    /**
     * Parses nullable date.
     */
    private LocalDate asNullableDate(Object value) {
        return asNullableDate(value, null);
    }

    /**
     * Parses nullable date with explicit default.
     */
    private LocalDate asNullableDate(Object value, LocalDate defaultValue) {
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    /**
     * Immutable recurring definition model used by in-memory store.
     */
    private record RecurringItem(
        Long id,
        Long userId,
        String name,
        int type,
        BigDecimal amount,
        String recurrenceType,
        int intervalCount,
        Integer dayOfWeek,
        Integer dayOfMonth,
        Integer monthOfYear,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate lastExecutionDate,
        LocalDate nextExecutionDate,
        int status,
        int executionCount,
        String remark
    ) {
    }
}
