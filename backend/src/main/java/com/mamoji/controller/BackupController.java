package com.mamoji.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.entity.User;
import com.mamoji.repository.AccountRepository;
import com.mamoji.repository.BudgetRepository;
import com.mamoji.repository.CategoryRepository;
import com.mamoji.repository.LedgerRepository;
import com.mamoji.repository.TransactionRepository;
import com.mamoji.security.AuthenticationUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Backup/export/import endpoints for user financial data.
 */
@RestController
@RequestMapping("/api/v1/backup")
@RequiredArgsConstructor
public class BackupController {

    private final AccountRepository accountRepository;
    private final BudgetRepository budgetRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerRepository ledgerRepository;
    private final ObjectMapper objectMapper;

    /**
     * Returns backup-related data volume statistics.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@AuthenticationUser User user) {
        Map<String, Object> data = new HashMap<>();
        data.put("users", 1);
        data.put("accounts", accountRepository.countByUserIdAndStatus(user.getId(), 1));
        data.put("categories", categoryRepository.findByFamilyIdOrIsSystem(user.getFamilyId(), 1).size());
        data.put("transactions", transactionRepository.countByUserId(user.getId()));
        data.put("budgets", budgetRepository.countByUserIdAndStatus(user.getId(), 1));
        data.put("ledgers", ledgerRepository.countByOwnerIdAndStatus(user.getId(), 1));
        return ResponseEntity.ok(wrapSuccess(data));
    }

    /**
     * Exports user data as JSON attachment.
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@AuthenticationUser User user) throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("exportedAt", LocalDateTime.now().toString());
        data.put("userId", user.getId());
        data.put("accounts", accountRepository.findByUserIdAndStatus(user.getId(), 1));
        data.put("categories", categoryRepository.findByFamilyIdOrIsSystem(user.getFamilyId(), 1));
        data.put("transactions", transactionRepository.findByUserIdAndDateBetween(
            user.getId(),
            LocalDateTime.now().minusYears(10).toLocalDate(),
            LocalDateTime.now().plusDays(1).toLocalDate()
        ));
        data.put("budgets", budgetRepository.findByUserIdAndStatus(user.getId(), 1));
        data.put("ledgers", ledgerRepository.findByOwnerIdAndStatus(user.getId(), 1));

        byte[] body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
        String fileName = "mamoji-backup-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".json";

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
            .contentType(new MediaType("application", "json", StandardCharsets.UTF_8))
            .body(body);
    }

    /**
     * Validates backup file format and returns import placeholder response.
     */
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> importBackup(
        @AuthenticationUser User user,
        @RequestParam("file") MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.ok(wrapError(4001, "文件为空"));
        }
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!(fileName.endsWith(".json") || fileName.endsWith(".zip"))) {
            return ResponseEntity.ok(wrapError(4002, "仅支持 .json 或 .zip 文件"));
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("importedCount", 0);
        payload.put("userId", user.getId());
        payload.put("note", "当前版本仅做文件校验，导入写库功能待实现");
        return ResponseEntity.ok(wrapSuccess(payload));
    }

    /**
     * Builds standard success envelope.
     */
    private Map<String, Object> wrapSuccess(Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", data);
        return result;
    }

    /**
     * Builds standard error envelope.
     */
    private Map<String, Object> wrapError(int code, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("message", message);
        result.put("data", null);
        return result;
    }
}
