package com.mamoji.common.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ApiResponses {

    private ApiResponses() {
    }

    public static ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(body(0, "success", data));
    }

    public static ResponseEntity<Map<String, Object>> status(HttpStatus status, int code, String message) {
        return ResponseEntity.status(status).body(body(code, message, null));
    }

    public static ResponseEntity<Map<String, Object>> badRequest(int code, String message) {
        return status(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ResponseEntity<Map<String, Object>> forbidden(int code, String message) {
        return status(HttpStatus.FORBIDDEN, code, message);
    }

    public static Map<String, Object> body(int code, String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", data);
        return body;
    }
}
