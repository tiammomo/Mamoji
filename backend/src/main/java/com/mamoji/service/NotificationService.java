package com.mamoji.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import com.mamoji.common.Roles;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.Employee;
import com.mamoji.domain.Models.OutboxEvent;
import com.mamoji.domain.Models.ReceiptVoucher;
import com.mamoji.domain.Models.TaxItem;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import com.mamoji.service.support.WebhookUrlValidator;
import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    private final InMemoryStore store;
    private final EnterpriseStore enterpriseStore;
    private final AccessControlService accessControl;
    private final NotificationDeliveryService deliveryService;
    private final WebhookUrlValidator webhookUrlValidator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final boolean reminderEnabled;
    private final int taxLookaheadDays;
    private final int peopleLookaheadDays;
    private final int receiptLookaheadDays;

    public NotificationService(
        JdbcTemplate jdbc,
        InMemoryStore store,
        EnterpriseStore enterpriseStore,
        AccessControlService accessControl,
        NotificationDeliveryService deliveryService,
        WebhookUrlValidator webhookUrlValidator,
        @Value("${mamoji.notifications.enabled:true}") boolean enabled,
        @Value("${mamoji.notifications.reminder.enabled:true}") boolean reminderEnabled,
        @Value("${mamoji.notifications.reminder.tax-lookahead-days:7}") int taxLookaheadDays,
        @Value("${mamoji.notifications.reminder.people-lookahead-days:14}") int peopleLookaheadDays,
        @Value("${mamoji.notifications.reminder.receipt-lookahead-days:7}") int receiptLookaheadDays
    ) {
        this.jdbc = jdbc;
        this.store = store;
        this.enterpriseStore = enterpriseStore;
        this.accessControl = accessControl;
        this.deliveryService = deliveryService;
        this.webhookUrlValidator = webhookUrlValidator;
        this.enabled = enabled;
        this.reminderEnabled = reminderEnabled;
        this.taxLookaheadDays = Math.max(1, taxLookaheadDays);
        this.peopleLookaheadDays = Math.max(1, peopleLookaheadDays);
        this.receiptLookaheadDays = Math.max(1, receiptLookaheadDays);
    }

    @PostConstruct
    void ensureSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS notifications (
                id BIGSERIAL PRIMARY KEY,
                user_id BIGINT NOT NULL,
                company_id BIGINT NOT NULL DEFAULT 0,
                type TEXT NOT NULL,
                severity TEXT NOT NULL DEFAULT 'info',
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                target_url TEXT,
                source_type TEXT,
                source_id BIGINT,
                dedupe_key TEXT,
                read_at TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_notifications_user_created ON notifications(user_id, created_at DESC, id DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_notifications_user_unread ON notifications(user_id, read_at, created_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_notifications_company_created ON notifications(company_id, created_at DESC, id DESC)");
        jdbc.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_notifications_user_dedupe
            ON notifications(user_id, dedupe_key)
            WHERE dedupe_key IS NOT NULL AND dedupe_key <> ''
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS notification_preferences (
                user_id BIGINT PRIMARY KEY,
                enabled BOOLEAN NOT NULL DEFAULT true,
                webhook_enabled BOOLEAN NOT NULL DEFAULT false,
                webhook_provider TEXT NOT NULL DEFAULT 'generic',
                webhook_url TEXT,
                min_severity TEXT NOT NULL DEFAULT 'info',
                muted_types TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PagedResponse<NotificationView> list(String authorization, Map<String, String> params) {
        User user = accessControl.requireUser(authorization);
        boolean unreadOnly = bool(params.get("unreadOnly"));
        PageRequest pageRequest = PageRequest.from(params);
        String unreadClause = unreadOnly ? " AND read_at IS NULL" : "";
        Long total = jdbc.queryForObject(
            "SELECT count(*) FROM notifications WHERE user_id = ?" + unreadClause,
            Long.class,
            user.id
        );
        List<NotificationView> notifications = jdbc.query(
            "SELECT * FROM notifications WHERE user_id = ?" + unreadClause + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
            this::mapNotification,
            user.id,
            pageRequest.size(),
            (long) pageRequest.page() * pageRequest.size()
        );
        long totalElements = total == null ? 0 : total;
        int totalPages = (int) Math.ceil((double) totalElements / pageRequest.size());
        return new PagedResponse<>(notifications, totalElements, totalPages, pageRequest.size(), pageRequest.page());
    }

    public Map<String, Object> summary(String authorization) {
        User user = accessControl.requireUser(authorization);
        return jdbc.queryForObject("""
            SELECT
                (SELECT COUNT(*) FROM notifications WHERE user_id = ? AND read_at IS NULL) AS unread_count,
                (SELECT COUNT(*) FROM notification_deliveries
                    WHERE user_id = ? AND status IN ('pending', 'failed', 'processing')) AS pending_delivery_count,
                (SELECT COUNT(*) FROM notification_deliveries
                    WHERE user_id = ? AND status = 'dead') AS failed_delivery_count
            """, (rs, rowNum) -> Map.of(
            "unreadCount", rs.getLong("unread_count"),
            "pendingDeliveryCount", rs.getLong("pending_delivery_count"),
            "failedDeliveryCount", rs.getLong("failed_delivery_count")
        ), user.id, user.id, user.id);
    }

    public NotificationPreference preference(String authorization) {
        User user = accessControl.requireUser(authorization);
        return preferenceFor(user.id);
    }

    public NotificationPreference updatePreference(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        NotificationPreference current = preferenceFor(user.id);
        boolean enabled = boolValue(body.get("enabled"), current.enabled());
        boolean webhookEnabled = boolValue(body.get("webhookEnabled"), current.webhookEnabled());
        String webhookProvider = normalizeProvider(text(body.get("webhookProvider"), current.webhookProvider()));
        String webhookUrl = body.containsKey("webhookUrl")
            ? blankToNull(text(body.get("webhookUrl"), ""))
            : current.webhookUrl();
        String minSeverity = normalizeSeverity(text(body.get("minSeverity"), current.minSeverity()));
        List<String> mutedTypes = body.containsKey("mutedTypes") ? parseTypes(body.get("mutedTypes")) : current.mutedTypes();
        if (webhookUrl != null && (webhookEnabled || body.containsKey("webhookUrl"))) {
            webhookUrl = safeWebhookUrl(webhookUrl);
        }
        if (webhookEnabled && webhookUrl == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook URL is required when webhook delivery is enabled");
        }
        String now = InMemoryStore.now();
        jdbc.update("""
            INSERT INTO notification_preferences (
                user_id, enabled, webhook_enabled, webhook_provider, webhook_url,
                min_severity, muted_types, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET
                enabled = excluded.enabled,
                webhook_enabled = excluded.webhook_enabled,
                webhook_provider = excluded.webhook_provider,
                webhook_url = excluded.webhook_url,
                min_severity = excluded.min_severity,
                muted_types = excluded.muted_types,
                updated_at = excluded.updated_at
            """,
            user.id,
            enabled,
            webhookEnabled,
            webhookProvider,
            webhookUrl,
            minSeverity,
            String.join(",", mutedTypes),
            now,
            now
        );
        return preferenceFor(user.id);
    }

    public Map<String, Object> testWebhook(String authorization) {
        User user = accessControl.requireUser(authorization);
        NotificationPreference preference = preferenceFor(user.id);
        if (!preference.webhookEnabled() || preference.webhookUrl() == null || preference.webhookUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook is not configured");
        }
        safeWebhookUrl(preference.webhookUrl());
        deliveryService.sendTestWebhook(preference);
        return Map.of("success", true);
    }

    public NotificationView markRead(String authorization, long id) {
        User user = accessControl.requireUser(authorization);
        NotificationView existing = findForUser(user.id, id);
        if (existing.readAt() == null || existing.readAt().isBlank()) {
            String now = InMemoryStore.now();
            jdbc.update("UPDATE notifications SET read_at = ?, updated_at = ? WHERE id = ? AND user_id = ?", now, now, id, user.id);
        }
        return findForUser(user.id, id);
    }

    public Map<String, Object> markAllRead(String authorization) {
        User user = accessControl.requireUser(authorization);
        String now = InMemoryStore.now();
        int updated = jdbc.update(
            "UPDATE notifications SET read_at = ?, updated_at = ? WHERE user_id = ? AND read_at IS NULL",
            now,
            now,
            user.id
        );
        return Map.of("updated", updated);
    }

    public void handleOutboxEvent(OutboxEvent event) {
        if (!enabled) {
            return;
        }
        Map<String, Object> payload = payload(event);
        String summary = text(payload.get("summary"), event.eventType);
        String dedupeKey = "outbox:" + event.eventId;
        if (event.eventType.startsWith("payroll.run.")) {
            String period = text(payload.get("period"), "");
            boolean closed = event.eventType.endsWith(".closed");
            notifyUsers(payrollRecipients(event.companyId, event.actorUserId), draft(
                event.companyId,
                "payroll",
                closed ? "success" : "info",
                closed ? "薪酬月结已锁定" : "薪酬月结已生成",
                period.isBlank() ? summary : "期间 " + period + "：" + summary,
                "/admin/compensation",
                event.aggregateType,
                event.aggregateId,
                dedupeKey
            ));
            return;
        }
        if (event.eventType.startsWith("receipt_voucher.")) {
            notifyUsers(financeRecipients(event.companyId, event.actorUserId), draft(
                event.companyId,
                "receipt",
                receiptSeverity(payload),
                "票据凭证有新进展",
                summary,
                "/receipts",
                event.aggregateType,
                event.aggregateId,
                dedupeKey
            ));
            return;
        }
        if (event.eventType.startsWith("enterprise.tax_item.")) {
            notifyUsers(financeRecipients(event.companyId, event.actorUserId), draft(
                event.companyId,
                "tax",
                event.eventType.endsWith(".delete") ? "warning" : "info",
                "税务事项已更新",
                summary,
                "/tax",
                event.aggregateType,
                event.aggregateId,
                dedupeKey
            ));
            return;
        }
        if (event.eventType.startsWith("enterprise.employee.") || event.eventType.startsWith("enterprise.department.")) {
            notifyUsers(peopleRecipients(event.companyId, event.actorUserId), draft(
                event.companyId,
                "people",
                "info",
                event.eventType.startsWith("enterprise.employee.") ? "员工档案已更新" : "组织架构已更新",
                summary,
                "/hr/organization",
                event.aggregateType,
                event.aggregateId,
                dedupeKey
            ));
            return;
        }
        if (event.eventType.startsWith("enterprise.entity_transfer.") || event.eventType.startsWith("accounting.account.")) {
            notifyUsers(financeRecipients(event.companyId, event.actorUserId), draft(
                event.companyId,
                "finance",
                "info",
                event.eventType.startsWith("enterprise.entity_transfer.") ? "主体往来已记录" : "资金账户已更新",
                summary,
                event.eventType.startsWith("enterprise.entity_transfer.") ? "/dashboard" : "/accounts",
                event.aggregateType,
                event.aggregateId,
                dedupeKey
            ));
            return;
        }
        if ("auth.registration_invite.created".equals(event.eventType)) {
            notifyUsers(actorOnly(event.actorUserId), draft(
                0,
                "system",
                "success",
                "注册邀请已创建",
                "邀请已生成：" + text(payload.get("email"), "新用户"),
                "/admin/users",
                event.aggregateType,
                event.aggregateId,
                dedupeKey
            ));
            return;
        }
        if ("auth.user.registered".equals(event.eventType)) {
            notifyUsers(adminRecipients(), draft(
                0,
                "system",
                "info",
                "新用户已注册",
                text(payload.get("email"), "新用户") + " 已完成注册",
                "/admin/users",
                event.aggregateType,
                event.aggregateId,
                dedupeKey
            ));
            notifyUsers(actorOnly(event.actorUserId), draft(
                0,
                "system",
                "success",
                "欢迎使用 Mamoji",
                "你的账号已创建，可以开始维护经营数据",
                "/dashboard",
                event.aggregateType,
                event.aggregateId,
                dedupeKey + ":welcome"
            ));
            return;
        }
        if (event.actorUserId > 0) {
            notifyUsers(actorOnly(event.actorUserId), draft(
                event.companyId,
                "system",
                "info",
                "操作已完成",
                summary,
                "/dashboard",
                event.aggregateType,
                event.aggregateId,
                dedupeKey
            ));
        }
    }

    @Scheduled(fixedDelayString = "${mamoji.notifications.reminder.fixed-delay-ms:60000}")
    public void generateDueReminders() {
        if (!enabled || !reminderEnabled) {
            return;
        }
        LocalDate today = LocalDate.now();
        generateTaxReminders(today);
        generatePeopleReminders(today);
        generateReceiptReminders(today);
    }

    private void generateTaxReminders(LocalDate today) {
        LocalDate latest = today.plusDays(taxLookaheadDays);
        for (TaxItem item : enterpriseStore.taxItems.values()) {
            if (taxSettled(item)) {
                continue;
            }
            LocalDate dueDate = parseDate(item.dueDate);
            if (dueDate == null || dueDate.isAfter(latest)) {
                continue;
            }
            boolean overdue = dueDate.isBefore(today);
            notifyUsers(financeRecipients(item.companyId, 0), draft(
                item.companyId,
                "tax",
                overdue ? "critical" : "warning",
                overdue ? "税务事项已逾期" : "税务事项即将到期",
                item.name + " " + item.period + " 截止日 " + item.dueDate,
                "/tax",
                "tax_item",
                item.id,
                "reminder:tax:" + item.id + ":" + today
            ));
        }
    }

    private void generatePeopleReminders(LocalDate today) {
        LocalDate latest = today.plusDays(peopleLookaheadDays);
        for (Employee employee : enterpriseStore.employees.values()) {
            if ("departed".equals(employee.status)) {
                continue;
            }
            remindPeopleDate(today, latest, employee, "contract", employee.contractEndDate, "劳动合同到期提醒");
            remindPeopleDate(today, latest, employee, "probation", employee.probationEndDate, "试用期到期提醒");
        }
    }

    private void remindPeopleDate(LocalDate today, LocalDate latest, Employee employee, String kind, String dateText, String title) {
        LocalDate date = parseDate(dateText);
        if (date == null || date.isAfter(latest)) {
            return;
        }
        boolean overdue = date.isBefore(today);
        notifyUsers(peopleRecipients(employee.companyId, 0), draft(
            employee.companyId,
            "people",
            overdue ? "critical" : "warning",
            overdue ? title.replace("提醒", "已逾期") : title,
            employee.name + "：" + dateText,
            "/hr/organization",
            "employee",
            employee.id,
            "reminder:people:" + kind + ":" + employee.id + ":" + today
        ));
    }

    private void generateReceiptReminders(LocalDate today) {
        LocalDate latest = today.plusDays(receiptLookaheadDays);
        for (ReceiptVoucher voucher : enterpriseStore.receiptVouchers.values()) {
            if (receiptClosed(voucher)) {
                continue;
            }
            LocalDate dueDate = parseDate(voucher.dueDate);
            if (dueDate == null || dueDate.isAfter(latest)) {
                continue;
            }
            boolean overdue = dueDate.isBefore(today);
            notifyUsers(financeRecipients(voucher.companyId, voucher.operatorUserId), draft(
                voucher.companyId,
                "receipt",
                overdue ? "critical" : "warning",
                overdue ? "票据凭证已逾期" : "票据凭证即将到期",
                voucher.title + " 截止日 " + voucher.dueDate,
                "/receipts",
                "receipt_voucher",
                voucher.id,
                "reminder:receipt:" + voucher.id + ":" + today
            ));
        }
    }

    private NotificationView findForUser(long userId, long id) {
        List<NotificationView> results = jdbc.query(
            "SELECT * FROM notifications WHERE id = ? AND user_id = ?",
            this::mapNotification,
            id,
            userId
        );
        if (results.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
        return results.get(0);
    }

    private void notifyUsers(Collection<Long> userIds, NotificationDraft draft) {
        if (!enabled || userIds == null || userIds.isEmpty()) {
            return;
        }
        for (Long userId : userIds.stream().filter(Objects::nonNull).distinct().toList()) {
            notifyUser(userId, draft);
        }
    }

    private void notifyUser(long userId, NotificationDraft draft) {
        if (!store.users.containsKey(userId)) {
            return;
        }
        NotificationPreference preference = preferenceFor(userId);
        if (!preference.enabled() || preference.mutedTypes().contains(draft.type()) || !severityEnabled(draft.severity(), preference.minSeverity())) {
            return;
        }
        String now = InMemoryStore.now();
        List<Long> insertedIds = jdbc.query("""
            INSERT INTO notifications (
                user_id, company_id, type, severity, title, content, target_url,
                source_type, source_id, dedupe_key, read_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
            ON CONFLICT DO NOTHING
            RETURNING id
            """,
            (rs, rowNum) -> rs.getLong("id"),
            userId,
            draft.companyId(),
            normalize(draft.type(), "system"),
            normalize(draft.severity(), "info"),
            normalize(draft.title(), "新通知"),
            normalize(draft.content(), ""),
            blankToNull(draft.targetUrl()),
            blankToNull(draft.sourceType()),
            draft.sourceId(),
            blankToNull(draft.dedupeKey()),
            now,
            now
        );
        if (!insertedIds.isEmpty() && preference.webhookEnabled() && preference.webhookUrl() != null && !preference.webhookUrl().isBlank()) {
            deliveryService.enqueueWebhook(insertedIds.get(0), userId, preference.webhookProvider());
        }
    }

    private Set<Long> payrollRecipients(long companyId, long actorUserId) {
        return withActor(companyRoleRecipients(companyId, "founder", "finance_admin", "hr_admin"), actorUserId);
    }

    private Set<Long> financeRecipients(long companyId, long actorUserId) {
        return withActor(companyRoleRecipients(companyId, "founder", "finance_admin"), actorUserId);
    }

    private Set<Long> peopleRecipients(long companyId, long actorUserId) {
        return withActor(companyRoleRecipients(companyId, "founder", "hr_admin"), actorUserId);
    }

    private Set<Long> companyRoleRecipients(long companyId, String... roles) {
        Set<Long> recipients = adminRecipients();
        if (companyId <= 0) {
            return recipients;
        }
        Set<String> roleSet = Set.of(roles);
        Company company = enterpriseStore.companies.get(companyId);
        if (company != null && roleSet.contains("founder")) {
            recipients.add(company.ownerId);
        }
        enterpriseStore.employees.values().stream()
            .filter(employee -> employee.companyId == companyId)
            .filter(employee -> employee.userId != null)
            .filter(employee -> !"departed".equals(employee.status))
            .filter(employee -> roleSet.contains(employee.accessRole))
            .map(employee -> employee.userId)
            .forEach(recipients::add);
        recipients.removeIf(userId -> !store.users.containsKey(userId));
        return recipients;
    }

    private Set<Long> adminRecipients() {
        Set<Long> recipients = new LinkedHashSet<>();
        store.users.values().stream()
            .filter(user -> user.role == Roles.ADMIN)
            .map(user -> user.id)
            .forEach(recipients::add);
        return recipients;
    }

    private Set<Long> actorOnly(long actorUserId) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (actorUserId > 0 && store.users.containsKey(actorUserId)) {
            recipients.add(actorUserId);
        }
        return recipients;
    }

    private Set<Long> withActor(Set<Long> recipients, long actorUserId) {
        Set<Long> all = new LinkedHashSet<>(recipients);
        if (actorUserId > 0 && store.users.containsKey(actorUserId)) {
            all.add(actorUserId);
        }
        return all;
    }

    private NotificationDraft draft(
        long companyId,
        String type,
        String severity,
        String title,
        String content,
        String targetUrl,
        String sourceType,
        Long sourceId,
        String dedupeKey
    ) {
        return new NotificationDraft(companyId, type, severity, title, content, targetUrl, sourceType, sourceId, dedupeKey);
    }

    private NotificationDraft draft(
        long companyId,
        String type,
        String severity,
        String title,
        String content,
        String targetUrl,
        String sourceType,
        long sourceId,
        String dedupeKey
    ) {
        return draft(companyId, type, severity, title, content, targetUrl, sourceType, Long.valueOf(sourceId), dedupeKey);
    }

    private NotificationView mapNotification(ResultSet rs, int rowNum) throws SQLException {
        return new NotificationView(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getLong("company_id"),
            rs.getString("type"),
            rs.getString("severity"),
            rs.getString("title"),
            rs.getString("content"),
            rs.getString("target_url"),
            rs.getString("source_type"),
            nullableLong(rs, "source_id"),
            rs.getString("read_at"),
            rs.getString("created_at"),
            rs.getString("updated_at")
        );
    }

    private Map<String, Object> payload(OutboxEvent event) {
        if (event.payloadJson == null || event.payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(event.payloadJson, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private NotificationPreference preferenceFor(long userId) {
        List<NotificationPreference> preferences = jdbc.query("""
            SELECT * FROM notification_preferences WHERE user_id = ?
            """, this::mapPreference, userId);
        if (!preferences.isEmpty()) {
            return preferences.get(0);
        }
        String now = InMemoryStore.now();
        jdbc.update("""
            INSERT INTO notification_preferences (
                user_id, enabled, webhook_enabled, webhook_provider, webhook_url,
                min_severity, muted_types, created_at, updated_at
            ) VALUES (?, true, false, 'generic', NULL, 'info', '', ?, ?)
            ON CONFLICT DO NOTHING
            """, userId, now, now);
        return new NotificationPreference(userId, true, false, "generic", null, "info", List.of(), now, now);
    }

    private NotificationPreference mapPreference(ResultSet rs, int rowNum) throws SQLException {
        return new NotificationPreference(
            rs.getLong("user_id"),
            rs.getBoolean("enabled"),
            rs.getBoolean("webhook_enabled"),
            normalizeProvider(rs.getString("webhook_provider")),
            rs.getString("webhook_url"),
            normalizeSeverity(rs.getString("min_severity")),
            parseTypeCsv(rs.getString("muted_types")),
            rs.getString("created_at"),
            rs.getString("updated_at")
        );
    }

    private String receiptSeverity(Map<String, Object> payload) {
        String status = text(payload.get("status"), "").toLowerCase(Locale.ROOT);
        if ("rejected".equals(status)) {
            return "critical";
        }
        if ("verified".equals(status) || "archived".equals(status) || "linked".equals(status)) {
            return "success";
        }
        return "info";
    }

    private boolean taxSettled(TaxItem item) {
        return "paid".equals(item.status) || "paid".equals(item.paymentStatus);
    }

    private boolean receiptClosed(ReceiptVoucher voucher) {
        return "archived".equals(voucher.status) || "linked".equals(voucher.status) || "verified".equals(voucher.status);
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean bool(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private boolean boolValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private boolean severityEnabled(String severity, String minSeverity) {
        return severityRank(normalizeSeverity(severity)) >= severityRank(normalizeSeverity(minSeverity));
    }

    private int severityRank(String severity) {
        return switch (normalizeSeverity(severity)) {
            case "success", "info" -> 1;
            case "warning" -> 2;
            case "critical" -> 3;
            default -> 1;
        };
    }

    private String normalizeSeverity(String severity) {
        return switch (severity == null ? "" : severity) {
            case "success", "info", "warning", "critical" -> severity;
            default -> "info";
        };
    }

    private String normalizeProvider(String provider) {
        return switch (provider == null ? "" : provider) {
            case "feishu", "wecom" -> provider;
            default -> "generic";
        };
    }

    private List<String> parseTypes(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
        }
        return parseTypeCsv(value == null ? "" : String.valueOf(value));
    }

    private List<String> parseTypeCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(",")).stream()
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .distinct()
            .toList();
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String safeWebhookUrl(String value) {
        try {
            return webhookUrlValidator.requireSafeUrl(value).toASCIIString();
        } catch (WebhookUrlValidator.UnsafeWebhookUrlException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private record NotificationDraft(
        long companyId,
        String type,
        String severity,
        String title,
        String content,
        String targetUrl,
        String sourceType,
        Long sourceId,
        String dedupeKey
    ) {
    }

    public record NotificationView(
        long id,
        long userId,
        long companyId,
        String type,
        String severity,
        String title,
        String content,
        String targetUrl,
        String sourceType,
        Long sourceId,
        String readAt,
        String createdAt,
        String updatedAt
    ) {
    }

    public record NotificationPreference(
        long userId,
        boolean enabled,
        boolean webhookEnabled,
        String webhookProvider,
        String webhookUrl,
        String minSeverity,
        List<String> mutedTypes,
        String createdAt,
        String updatedAt
    ) {
    }
}
