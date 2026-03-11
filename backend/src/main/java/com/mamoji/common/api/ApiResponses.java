package com.mamoji.common.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standard API response factory utilities.
 */
public final class ApiResponses {

    /**
     * Utility class; do not instantiate.
     */
    private ApiResponses() {
    }

    /**
     * Builds a standard success response.
     */
    public static ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(body(0, "success", data));
    }

    /**
     * Builds a response with explicit HTTP status and business error code.
     */
    public static ResponseEntity<Map<String, Object>> status(HttpStatus status, int code, String message) {
        return ResponseEntity.status(status).body(body(code, message, null));
    }

    /**
     * Builds a bad-request response.
     */
    public static ResponseEntity<Map<String, Object>> badRequest(int code, String message) {
        return status(HttpStatus.BAD_REQUEST, code, message);
    }

    /**
     * Builds a forbidden response.
     */
    public static ResponseEntity<Map<String, Object>> forbidden(int code, String message) {
        return status(HttpStatus.FORBIDDEN, code, message);
    }

    /**
     * Creates the common response payload shape.
     */
    public static Map<String, Object> body(int code, String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", data);
        return body;
    }
}
