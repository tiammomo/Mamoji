package com.mamoji.controller;

import com.mamoji.service.MamojiService;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/receipts")
public class ReceiptController {
    private final MamojiService service;

    public ReceiptController(MamojiService service) {
        this.service = service;
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam("file") MultipartFile file
    ) {
        return service.uploadReceipt(authorization, file);
    }
}
