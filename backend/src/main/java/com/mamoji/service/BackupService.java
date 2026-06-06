package com.mamoji.service;

import com.mamoji.repository.InMemoryStore;
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
    private final AccessControlService accessControl;

    public BackupService(InMemoryStore store, AccessControlService accessControl) {
        this.store = store;
        this.accessControl = accessControl;
    }

    public Map<String, Integer> status(String authorization) {
        accessControl.requireUser(authorization);
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
        accessControl.requireUser(authorization);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDisposition(ContentDisposition.attachment().filename("mamoji-backup.json").build());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", "1.0");
        payload.put("exportedAt", InMemoryStore.now());
        payload.put("data", store.snapshot());
        return ResponseEntity.ok().headers(headers).body(payload);
    }

    public Map<String, Object> validate(String authorization, MultipartFile file) {
        accessControl.requireUser(authorization);
        boolean valid = file != null && !file.isEmpty() && file.getOriginalFilename() != null && file.getOriginalFilename().endsWith(".json");
        return Map.of("valid", valid, "message", valid ? "Backup file looks valid" : "Please upload a non-empty .json backup");
    }
}
