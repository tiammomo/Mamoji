package com.mamoji.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BackupService {
    private static final String FORMAT = "mamoji-structured-backup";
    private static final String VERSION = "2.0";
    private static final String RESTORE_CONFIRMATION = "RESTORE";
    private static final int MAX_BACKUP_BYTES = 50 * 1024 * 1024;

    /**
     * Business data included in the application-level portable snapshot.
     * Object payloads remain in MinIO and are protected by the production
     * postgres + MinIO backup scripts. Sessions, delivery attempts and outbox
     * leases are deliberately excluded because replaying them is unsafe.
     */
    private static final List<String> BACKUP_TABLES = List.of(
        "users",
        "registration_invites",
        "companies",
        "departments",
        "employees",
        "employee_certificates",
        "employee_experiences",
        "employment_events",
        "ledgers",
        "ledger_members",
        "accounts",
        "account_reconciliations",
        "categories",
        "budgets",
        "transactions",
        "recurring_items",
        "tax_items",
        "entity_transfers",
        "receipt_vouchers",
        "receipt_file_hashes",
        "payroll_runs",
        "payroll_run_items",
        "audit_logs",
        "notifications",
        "notification_preferences",
        "approval_requests",
        "approval_actions"
    );

    private static final List<String> RESET_ONLY_TABLES = List.of(
        "notification_deliveries",
        "outbox_events"
    );

    private final InMemoryStore store;
    private final EnterpriseStore enterpriseStore;
    private final AccessControlService accessControl;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate snapshotTransaction;

    public BackupService(
        InMemoryStore store,
        EnterpriseStore enterpriseStore,
        AccessControlService accessControl,
        JdbcTemplate jdbc,
        PlatformTransactionManager transactionManager
    ) {
        this.store = store;
        this.enterpriseStore = enterpriseStore;
        this.accessControl = accessControl;
        this.jdbc = jdbc;
        this.snapshotTransaction = new TransactionTemplate(transactionManager);
        this.snapshotTransaction.setReadOnly(true);
        this.snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_LONG_FOR_INTS);
    }

    public Map<String, Integer> status(String authorization) {
        accessControl.requireAdmin(authorization);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("users", count("users"));
        counts.put("accounts", count("accounts"));
        counts.put("categories", count("categories"));
        counts.put("transactions", count("transactions"));
        counts.put("budgets", count("budgets"));
        counts.put("ledgers", count("ledgers"));
        counts.put("employees", count("employees"));
        counts.put("taxItems", count("tax_items"));
        counts.put("receipts", count("receipt_vouchers"));
        counts.put("payrollRuns", count("payroll_runs"));
        counts.put("notifications", count("notifications"));
        counts.put("datasets", BACKUP_TABLES.size());
        return counts;
    }

    public ResponseEntity<Map<String, Object>> export(String authorization) {
        User user = accessControl.requireAdmin(authorization);
        Map<String, Object> data = snapshotTransaction.execute(status -> readDatasets());
        if (data == null) {
            throw new IllegalStateException("Failed to create a consistent backup snapshot");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("format", FORMAT);
        payload.put("version", VERSION);
        payload.put("exportedAt", OffsetDateTime.now().toString());
        payload.put("scope", "all-business-data");
        payload.put("objectStorage", Map.of(
            "included", false,
            "message", "Attachment metadata is included; MinIO object bytes require the production backup archive."
        ));
        payload.put("counts", datasetCounts(data));
        payload.put("data", data);
        payload.put("checksum", checksum(data));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDisposition(ContentDisposition.attachment()
            .filename("mamoji-structured-backup-" + java.time.LocalDate.now() + ".json")
            .build());
        enterpriseStore.auditLog(0, "backup", 0, "export", "导出全量结构化经营数据", user.id, user.nickname);
        return ResponseEntity.ok().headers(headers).body(payload);
    }

    public Map<String, Object> validate(String authorization, MultipartFile file) {
        accessControl.requireAdmin(authorization);
        try {
            ParsedBackup parsed = parseAndValidate(file);
            return validationResponse(parsed, true, "结构化备份完整性校验通过，可进入受控恢复。", false);
        } catch (IllegalArgumentException ex) {
            return Map.of(
                "valid", false,
                "restorable", false,
                "message", ex.getMessage()
            );
        }
    }

    @Transactional
    public Map<String, Object> restore(
        String authorization,
        MultipartFile file,
        String confirmation,
        boolean dryRun
    ) {
        User operator = accessControl.requireAdmin(authorization);
        ParsedBackup parsed;
        try {
            parsed = parseAndValidate(file);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        if (dryRun) {
            return validationResponse(parsed, true, "恢复预检通过，尚未写入任何数据。", true);
        }
        if (!RESTORE_CONFIRMATION.equals(confirmation)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type RESTORE to confirm the destructive restore");
        }

        List<String> resetTables = new ArrayList<>(BACKUP_TABLES);
        resetTables.addAll(RESET_ONLY_TABLES);
        jdbc.execute("TRUNCATE TABLE " + String.join(", ", resetTables) + " RESTART IDENTITY");
        for (String table : BACKUP_TABLES) {
            restoreTable(table, parsed.data().get(table));
            resetSequence(table);
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    store.reloadFromDatabase();
                    enterpriseStore.reloadFromDatabase();
                    enterpriseStore.auditLog(
                        0,
                        "backup",
                        0,
                        "restore",
                        "从结构化备份恢复全部业务数据",
                        operator.id,
                        operator.nickname
                    );
                }
            });
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("restored", true);
        result.put("message", "结构化业务数据已恢复；当前登录会话保持不变。附件文件请使用配套 MinIO 备份恢复。" );
        result.put("counts", datasetCounts(parsed.data()));
        result.put("restoredAt", OffsetDateTime.now().toString());
        return result;
    }

    private Map<String, Object> readDatasets() {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String table : BACKUP_TABLES) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM " + table + orderBy(table));
            List<Map<String, Object>> normalizedRows = rows.stream().map(this::normalizeRow).toList();
            data.put(table, normalizedRows);
        }
        return data;
    }

    private String orderBy(String table) {
        return hasColumn(table, "id") ? " ORDER BY id" : " ORDER BY 1";
    }

    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), normalizeValue(value)));
        return normalized;
    }

    private Object normalizeValue(Object value) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return java.util.Base64.getEncoder().encodeToString(bytes);
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private ParsedBackup parseAndValidate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择非空的 JSON 备份文件。" );
        }
        String fileName = Objects.toString(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        if (!fileName.endsWith(".json")) {
            throw new IllegalArgumentException("仅支持系统导出的 .json 结构化备份。" );
        }
        if (file.getSize() > MAX_BACKUP_BYTES) {
            throw new IllegalArgumentException("结构化备份不能超过 50MB。" );
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(file.getBytes(), new TypeReference<>() {});
            if (!FORMAT.equals(payload.get("format")) || !VERSION.equals(payload.get("version"))) {
                throw new IllegalArgumentException("备份格式或版本不兼容。" );
            }
            Object rawData = payload.get("data");
            if (!(rawData instanceof Map<?, ?> rawMap)) {
                throw new IllegalArgumentException("备份缺少 data 数据集。" );
            }
            Map<String, Object> data = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> data.put(String.valueOf(key), value));
            for (String table : BACKUP_TABLES) {
                if (!(data.get(table) instanceof List<?>)) {
                    throw new IllegalArgumentException("备份缺少数据集: " + table);
                }
            }
            if (((List<?>) data.get("users")).isEmpty() || ((List<?>) data.get("companies")).isEmpty()) {
                throw new IllegalArgumentException("备份必须至少包含一个用户和一个主体。" );
            }
            String expectedChecksum = Objects.toString(payload.get("checksum"), "");
            String actualChecksum = checksum(data);
            if (expectedChecksum.isBlank() || !MessageDigest.isEqual(
                expectedChecksum.getBytes(StandardCharsets.UTF_8),
                actualChecksum.getBytes(StandardCharsets.UTF_8)
            )) {
                throw new IllegalArgumentException("备份校验和不匹配，文件可能不完整或已被修改。" );
            }
            return new ParsedBackup(data, actualChecksum);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法解析备份文件，请确认文件来自当前版本系统。" );
        }
    }

    private Map<String, Object> validationResponse(
        ParsedBackup parsed,
        boolean valid,
        String message,
        boolean dryRun
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", valid);
        result.put("restorable", valid);
        result.put("dryRun", dryRun);
        result.put("format", FORMAT);
        result.put("version", VERSION);
        result.put("message", message);
        result.put("counts", datasetCounts(parsed.data()));
        result.put("checksum", parsed.checksum());
        result.put("attachmentBytesIncluded", false);
        return result;
    }

    private Map<String, Integer> datasetCounts(Map<String, Object> data) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : BACKUP_TABLES) {
            Object rows = data.get(table);
            counts.put(table, rows instanceof List<?> list ? list.size() : 0);
        }
        return counts;
    }

    private String checksum(Map<String, Object> data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(data)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to calculate backup checksum", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private void restoreTable(String table, Object rawRows) {
        List<?> rows = (List<?>) rawRows;
        if (rows.isEmpty()) {
            return;
        }
        Map<String, String> columnTypes = columnTypes(table);
        Map<String, Object> first = castRow(rows.getFirst(), table);
        List<String> columns = new ArrayList<>(first.keySet());
        if (columns.isEmpty() || columns.stream().anyMatch(column -> !columnTypes.containsKey(column))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Backup contains unknown columns for " + table);
        }
        String placeholders = String.join(", ", columns.stream().map(column -> "?").toList());
        String sql = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES (" + placeholders + ")";
        List<Object> rowBatch = new ArrayList<>(rows);
        jdbc.batchUpdate(sql, rowBatch, 100, (PreparedStatement statement, Object rawRow) -> {
            Map<String, Object> row = castRow(rawRow, table);
            if (!row.keySet().equals(first.keySet())) {
                throw new IllegalArgumentException("Inconsistent columns in dataset " + table);
            }
            for (int index = 0; index < columns.size(); index += 1) {
                setValue(statement, index + 1, row.get(columns.get(index)), columnTypes.get(columns.get(index)));
            }
        });
    }

    private Map<String, Object> castRow(Object rawRow, String table) {
        if (!(rawRow instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Invalid row in dataset " + table);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        map.forEach((key, value) -> row.put(String.valueOf(key), value));
        return row;
    }

    private void setValue(PreparedStatement statement, int index, Object rawValue, String dataType) throws java.sql.SQLException {
        if (rawValue == null) {
            statement.setNull(index, sqlType(dataType));
            return;
        }
        String value = String.valueOf(rawValue);
        switch (dataType) {
            case "bigint" -> statement.setLong(index, Long.parseLong(value));
            case "integer", "smallint" -> statement.setInt(index, Integer.parseInt(value));
            case "numeric", "decimal" -> statement.setBigDecimal(index, new BigDecimal(value));
            case "real" -> statement.setFloat(index, Float.parseFloat(value));
            case "double precision" -> statement.setDouble(index, Double.parseDouble(value));
            case "boolean" -> statement.setBoolean(index, Boolean.parseBoolean(value));
            case "bytea" -> statement.setBytes(index, java.util.Base64.getDecoder().decode(value));
            case "json", "jsonb" -> statement.setObject(index, value, Types.OTHER);
            default -> statement.setString(index, value);
        }
    }

    private int sqlType(String dataType) {
        return switch (dataType) {
            case "bigint" -> Types.BIGINT;
            case "integer", "smallint" -> Types.INTEGER;
            case "numeric", "decimal" -> Types.NUMERIC;
            case "real" -> Types.REAL;
            case "double precision" -> Types.DOUBLE;
            case "boolean" -> Types.BOOLEAN;
            case "bytea" -> Types.BINARY;
            default -> Types.VARCHAR;
        };
    }

    private Map<String, String> columnTypes(String table) {
        Map<String, String> types = new LinkedHashMap<>();
        jdbc.query("""
            SELECT column_name, data_type
            FROM information_schema.columns
            WHERE table_schema = current_schema() AND table_name = ?
            ORDER BY ordinal_position
            """, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                types.put(rs.getString("column_name"), rs.getString("data_type")), table);
        return types;
    }

    private boolean hasColumn(String table, String column) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?
            """, Integer.class, table, column);
        return count != null && count > 0;
    }

    private void resetSequence(String table) {
        if (!hasColumn(table, "id")) {
            return;
        }
        String sequence = jdbc.queryForObject("SELECT pg_get_serial_sequence(?, 'id')", String.class, table);
        if (sequence != null && !sequence.isBlank()) {
            jdbc.execute("SELECT setval(pg_get_serial_sequence('" + table + "', 'id'), COALESCE(MAX(id), 1), COUNT(*) > 0) FROM " + table);
        }
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private record ParsedBackup(Map<String, Object> data, String checksum) {}
}
