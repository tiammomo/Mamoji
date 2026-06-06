package com.mamoji.controller;

import com.mamoji.service.MamojiService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/backup")
public class BackupController {
    private final MamojiService service;

    public BackupController(MamojiService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public Map<String, Integer> status(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.backupStatus(authorization);
    }

    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.exportBackup(authorization);
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam("file") MultipartFile file
    ) {
        return service.validateBackup(authorization, file);
    }
}
