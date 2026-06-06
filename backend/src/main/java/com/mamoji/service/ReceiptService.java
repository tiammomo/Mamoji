package com.mamoji.service;

import com.mamoji.service.support.AccessControlService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReceiptService {
    private final AccessControlService accessControl;

    public ReceiptService(AccessControlService accessControl) {
        this.accessControl = accessControl;
    }

    public Map<String, Object> upload(String authorization, MultipartFile file) {
        accessControl.requireUser(authorization);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt image is required");
        }
        String filename = file.getOriginalFilename() == null ? "receipt" : file.getOriginalFilename();
        return Map.of(
            "success", true,
            "filename", filename,
            "size", file.getSize(),
            "message", "Receipt uploaded"
        );
    }
}
