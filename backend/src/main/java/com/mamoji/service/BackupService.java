package com.mamoji.service;

import com.mamoji.repository.InMemoryStore;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.domain.Models.User;
import com.mamoji.service.support.AccessControlService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class BackupService {
    private final InMemoryStore store;
    private final EnterpriseStore enterpriseStore;
    private final AccessControlService accessControl;

    public BackupService(InMemoryStore store, EnterpriseStore enterpriseStore, AccessControlService accessControl) {
        this.store = store;
        this.enterpriseStore = enterpriseStore;
        this.accessControl = accessControl;
    }

    public Map<String, Integer> status(String authorization) {
        accessControl.requireAdmin(authorization);
        return Map.of(
            "users", store.users.size(),
            "accounts", store.accounts.size(),
            "categories", store.categories.size(),
            "transactions", store.transactions.size(),
            "budgets", store.budgets.size(),
            "ledgers", store.ledgers.size()
        );
    }

    public ResponseEntity<Map<String, Object>> export(String authorization) {
        User user = accessControl.requireAdmin(authorization);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDisposition(ContentDisposition.attachment().filename("mamoji-backup.json").build());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", "1.0");
        payload.put("exportedAt", InMemoryStore.now());
        payload.put("data", store.snapshot());
        enterpriseStore.auditLog(0, "backup", 0, "export", "导出经营数据备份", user.id, user.nickname);
        return ResponseEntity.ok().headers(headers).body(payload);
    }

    public Map<String, Object> validate(String authorization, MultipartFile file) {
        accessControl.requireAdmin(authorization);
        boolean valid = file != null && !file.isEmpty() && file.getOriginalFilename() != null && file.getOriginalFilename().endsWith(".json");
        return Map.of("valid", valid, "message", valid ? "Backup file looks valid" : "Please upload a non-empty .json backup");
    }
}
