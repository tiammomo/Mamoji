package com.mamoji.service;

import com.mamoji.domain.Models.Account;
import com.mamoji.domain.Models.Category;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.optionalLong;

@Service
public class TransactionImportService {
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ROWS = 500;

    private final AccessControlService accessControl;
    private final InMemoryStore store;
    private final AccountingService accountingService;
    private final TransactionTemplate writeTransaction;

    public TransactionImportService(
        AccessControlService accessControl,
        InMemoryStore store,
        AccountingService accountingService,
        PlatformTransactionManager transactionManager
    ) {
        this.accessControl = accessControl;
        this.store = store;
        this.accountingService = accountingService;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    public byte[] template(String authorization) {
        accessControl.requireUser(authorization);
        String csv = "\uFEFF日期,类型,金额,分类,账户,备注\r\n"
            + LocalDate.now() + ",支出,99.00,办公采购,公司基本户,示例：办公用品\r\n"
            + LocalDate.now() + ",收入,1000.00,主营业务收入,公司基本户,示例：客户回款\r\n";
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    public Map<String, Object> preview(String authorization, MultipartFile file, Long companyId) {
        ImportContext context = context(authorization, companyId);
        ParsedImport parsed = parse(file, context);
        return response(parsed, 0, 0, List.of(), false);
    }

    public Map<String, Object> commit(
        String authorization,
        MultipartFile file,
        Long companyId,
        boolean skipDuplicates
    ) {
        ImportContext context = context(authorization, companyId);
        ParsedImport parsed = parse(file, context);
        if (parsed.invalidRows() > 0) {
            return response(parsed, 0, 0, List.of(), false);
        }
        Map<String, Object> result = writeTransaction.execute(status -> commitRows(
            authorization,
            context,
            parsed,
            skipDuplicates
        ));
        if (result == null) {
            throw new IllegalStateException("Transaction import did not produce a result");
        }
        return result;
    }

    private Map<String, Object> commitRows(
        String authorization,
        ImportContext context,
        ParsedImport parsed,
        boolean skipDuplicates
    ) {
        int imported = 0;
        int skipped = 0;
        List<Long> ids = new ArrayList<>();
        for (ImportRow row : parsed.rows()) {
            if (row.duplicate() && skipDuplicates) {
                skipped += 1;
                continue;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("companyId", context.company().id);
            body.put("type", row.type());
            body.put("amount", row.amount());
            body.put("categoryId", row.categoryId());
            body.put("accountId", row.accountId());
            body.put("date", row.date());
            body.put("note", row.note());
            Map<String, Object> created = accountingService.createTransaction(authorization, body);
            Object transaction = created.get("transaction");
            if (transaction instanceof TransactionRecord record) ids.add(record.id);
            imported += 1;
        }
        return response(parsed, imported, skipped, ids, true);
    }

    private ImportContext context(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        List<Account> accounts = store.accounts.values().stream()
            .filter(account -> account.userId == user.id && Objects.equals(account.companyId, company.id))
            .toList();
        List<Category> categories = store.categories.values().stream()
            .filter(category -> category.userId == user.id && Objects.equals(category.companyId, company.id))
            .toList();
        if (accounts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Create an account before importing transactions");
        }
        if (categories.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Create income and expense categories before importing transactions");
        }
        return new ImportContext(user, company, accounts, categories);
    }

    private ParsedImport parse(MultipartFile file, ImportContext context) {
        validateFile(file);
        try {
            String text = new String(file.getBytes(), StandardCharsets.UTF_8);
            List<List<String>> records = parseCsv(text);
            if (records.size() < 2) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV must include a header and at least one data row");
            }
            if (records.size() - 1 > MAX_ROWS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A single import supports at most 500 rows");
            }
            Map<String, Integer> headers = canonicalHeaders(records.getFirst());
            requireHeaders(headers, "date", "type", "amount", "category", "account");
            Set<String> existingKeys = existingKeys(context);
            Set<String> fileKeys = new HashSet<>();
            List<ImportRow> rows = new ArrayList<>();
            int validRows = 0;
            int duplicateRows = 0;
            for (int index = 1; index < records.size(); index += 1) {
                List<String> values = records.get(index);
                if (values.stream().allMatch(String::isBlank)) continue;
                ImportRow row = parseRow(index + 1, values, headers, context, existingKeys, fileKeys);
                rows.add(row);
                if (row.errors().isEmpty()) validRows += 1;
                if (row.duplicate()) duplicateRows += 1;
            }
            return new ParsedImport(rows, rows.size(), validRows, rows.size() - validRows, duplicateRows);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to parse the UTF-8 CSV file");
        }
    }

    private ImportRow parseRow(
        int rowNumber,
        List<String> values,
        Map<String, Integer> headers,
        ImportContext context,
        Set<String> existingKeys,
        Set<String> fileKeys
    ) {
        LinkedHashSet<String> errors = new LinkedHashSet<>();
        String rawDate = value(values, headers.get("date"));
        String rawType = value(values, headers.get("type"));
        String rawAmount = value(values, headers.get("amount"));
        String rawCategory = value(values, headers.get("category"));
        String rawAccount = value(values, headers.get("account"));
        String note = value(values, headers.get("note"));
        String date = parseDate(rawDate, errors);
        Integer type = parseType(rawType, errors);
        BigDecimal amount = parseAmount(rawAmount, errors);
        Category category = resolveCategory(rawCategory, type, context.categories(), errors);
        Account account = resolveAccount(rawAccount, context.accounts(), errors);
        if (note.length() > 200) errors.add("备注不能超过 200 字");

        boolean duplicate = false;
        if (errors.isEmpty()) {
            String key = transactionKey(type, amount, date, category.id, account.id, note);
            duplicate = existingKeys.contains(key) || !fileKeys.add(key);
        }
        return new ImportRow(
            rowNumber,
            date,
            type == null ? 0 : type,
            amount == null ? BigDecimal.ZERO : amount,
            category == null ? null : category.id,
            category == null ? rawCategory : category.name,
            account == null ? null : account.id,
            account == null ? rawAccount : account.name,
            note,
            duplicate,
            List.copyOf(errors)
        );
    }

    private Set<String> existingKeys(ImportContext context) {
        Set<String> keys = new HashSet<>();
        store.transactions.values().stream()
            .filter(transaction -> transaction.userId == context.user().id)
            .filter(transaction -> Objects.equals(transaction.companyId, context.company().id))
            .forEach(transaction -> keys.add(transactionKey(
                transaction.type,
                transaction.amount,
                transaction.date,
                transaction.categoryId,
                transaction.accountId,
                transaction.note
            )));
        return keys;
    }

    private String transactionKey(int type, BigDecimal amount, String date, long categoryId, long accountId, String note) {
        return type + "|" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + "|" + date + "|"
            + categoryId + "|" + accountId + "|" + Objects.toString(note, "").trim().toLowerCase(Locale.ROOT);
    }

    private String parseDate(String value, Set<String> errors) {
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.isAfter(LocalDate.now())) errors.add("日期不能晚于今天");
            if (date.isBefore(LocalDate.now().minusYears(20))) errors.add("日期不能早于 20 年前");
            return date.toString();
        } catch (DateTimeParseException ex) {
            errors.add("日期必须使用 yyyy-MM-dd");
            return value;
        }
    }

    private Integer parseType(String value, Set<String> errors) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (Set.of("1", "income", "收入", "收款").contains(normalized)) return 1;
        if (Set.of("2", "expense", "支出", "成本", "付款").contains(normalized)) return 2;
        errors.add("类型仅支持收入或支出");
        return null;
    }

    private BigDecimal parseAmount(String value, Set<String> errors) {
        try {
            String normalized = value.replace(",", "").replace("¥", "").replace("￥", "").trim();
            BigDecimal amount = new BigDecimal(normalized);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) errors.add("金额必须大于 0");
            if (amount.compareTo(new BigDecimal("10000000")) > 0) errors.add("金额不能超过 10,000,000");
            if (amount.scale() > 2) errors.add("金额最多保留两位小数");
            return amount.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            errors.add("金额格式不正确");
            return null;
        }
    }

    private Category resolveCategory(String value, Integer type, List<Category> categories, Set<String> errors) {
        Category category = null;
        try {
            long id = Long.parseLong(value);
            category = categories.stream().filter(item -> item.id == id).findFirst().orElse(null);
        } catch (NumberFormatException ignored) {
            category = categories.stream().filter(item -> item.name.equalsIgnoreCase(value.trim())).findFirst().orElse(null);
        }
        if (category == null) {
            errors.add("找不到分类「" + value + "」");
        } else if (type != null && !(type == 1 ? "income" : "expense").equals(category.type)) {
            errors.add("分类与收支类型不匹配");
        }
        return category;
    }

    private Account resolveAccount(String value, List<Account> accounts, Set<String> errors) {
        Account account = null;
        try {
            long id = Long.parseLong(value);
            account = accounts.stream().filter(item -> item.id == id).findFirst().orElse(null);
        } catch (NumberFormatException ignored) {
            account = accounts.stream().filter(item -> item.name.equalsIgnoreCase(value.trim())).findFirst().orElse(null);
        }
        if (account == null) errors.add("找不到账户「" + value + "」");
        else if (account.status != 1) errors.add("账户已停用或冻结");
        return account;
    }

    private Map<String, Integer> canonicalHeaders(List<String> rawHeaders) {
        Map<String, Integer> headers = new HashMap<>();
        for (int index = 0; index < rawHeaders.size(); index += 1) {
            String normalized = rawHeaders.get(index).replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
            String canonical = switch (normalized) {
                case "date", "日期", "交易日期", "流水日期" -> "date";
                case "type", "类型", "收支类型" -> "type";
                case "amount", "金额", "交易金额" -> "amount";
                case "category", "categoryid", "分类", "分类id" -> "category";
                case "account", "accountid", "账户", "账户id", "资金账户" -> "account";
                case "note", "memo", "备注", "摘要", "说明" -> "note";
                default -> null;
            };
            if (canonical != null) headers.putIfAbsent(canonical, index);
        }
        return headers;
    }

    private void requireHeaders(Map<String, Integer> headers, String... required) {
        for (String header : required) {
            if (!headers.containsKey(header)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV missing required column: " + header);
            }
        }
    }

    private String value(List<String> values, Integer index) {
        if (index == null || index < 0 || index >= values.size()) return "";
        return values.get(index).trim();
    }

    private List<List<String>> parseCsv(String input) {
        String text = input.replace("\uFEFF", "");
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index += 1) {
            char current = text.charAt(index);
            if (quoted) {
                if (current == '"' && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    field.append('"');
                    index += 1;
                } else if (current == '"') {
                    quoted = false;
                } else {
                    field.append(current);
                }
                continue;
            }
            if (current == '"' && field.isEmpty()) {
                quoted = true;
            } else if (current == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (current == '\n' || current == '\r') {
                if (current == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') index += 1;
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(current);
            }
        }
        if (quoted) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV contains an unclosed quoted field");
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a non-empty CSV file");
        String name = Objects.toString(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        if (!name.endsWith(".csv")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only .csv files are supported");
        if (file.getSize() > MAX_FILE_BYTES) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV cannot exceed 2MB");
    }

    private Map<String, Object> response(ParsedImport parsed, int imported, int skipped, List<Long> ids, boolean committed) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("committed", committed);
        response.put("totalRows", parsed.totalRows());
        response.put("validRows", parsed.validRows());
        response.put("invalidRows", parsed.invalidRows());
        response.put("duplicateRows", parsed.duplicateRows());
        response.put("importedRows", imported);
        response.put("skippedRows", skipped);
        response.put("transactionIds", ids);
        response.put("rows", parsed.rows());
        return response;
    }

    private record ImportContext(User user, Company company, List<Account> accounts, List<Category> categories) {}

    private record ParsedImport(List<ImportRow> rows, int totalRows, int validRows, int invalidRows, int duplicateRows) {}

    public record ImportRow(
        int rowNumber,
        String date,
        int type,
        BigDecimal amount,
        Long categoryId,
        String categoryName,
        Long accountId,
        String accountName,
        String note,
        boolean duplicate,
        List<String> errors
    ) {}
}
