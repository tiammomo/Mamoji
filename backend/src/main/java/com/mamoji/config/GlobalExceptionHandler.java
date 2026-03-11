package com.mamoji.config;

import com.mamoji.common.exception.BadRequestException;
import com.mamoji.common.exception.ForbiddenOperationException;
import com.mamoji.common.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Global exception translator for REST APIs.
 *
 * <p>Converts framework/business exceptions into a unified response payload:
 * {@code {traceId, code, message, data}}.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles unauthenticated access.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationException(
        AuthenticationException ex,
        HttpServletRequest request
    ) {
        return buildResponse(
            HttpStatus.UNAUTHORIZED,
            "Authentication is required.",
            request,
            ex
        );
    }

    /**
     * Handles permission-denied access.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDeniedException(
        AccessDeniedException ex,
        HttpServletRequest request
    ) {
        return buildResponse(
            HttpStatus.FORBIDDEN,
            "You do not have permission to access this resource.",
            request,
            ex
        );
    }

    /**
     * Handles bean validation failures.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentNotValidException(
        MethodArgumentNotValidException ex,
        HttpServletRequest request
    ) {
        String message = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .findFirst()
            .map(this::formatFieldError)
            .orElse("Request validation failed.");
        return buildResponse(HttpStatus.BAD_REQUEST, message, request, ex);
    }

    /**
     * Handles request parameter type mismatch.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
        MethodArgumentTypeMismatchException ex,
        HttpServletRequest request
    ) {
        String message = "Invalid parameter: " + ex.getName();
        return buildResponse(HttpStatus.BAD_REQUEST, message, request, ex);
    }

    /**
     * Handles illegal argument errors from application code.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(
        IllegalArgumentException ex,
        HttpServletRequest request
    ) {
        String message = ex.getMessage() != null && !ex.getMessage().isBlank()
            ? ex.getMessage()
            : "Invalid request.";
        return buildResponse(HttpStatus.BAD_REQUEST, message, request, ex);
    }

    /**
     * Handles business bad-request exceptions.
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequestException(
        BadRequestException ex,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request, ex);
    }

    /**
     * Handles business forbidden-operation exceptions.
     */
    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<Map<String, Object>> handleForbiddenOperationException(
        ForbiddenOperationException ex,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.FORBIDDEN, ex.getMessage(), request, ex);
    }

    /**
     * Handles resource-not-found exceptions.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleResourceNotFoundException(
        ResourceNotFoundException ex,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request, ex);
    }

    /**
     * Handles unresolved static resource requests.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFoundException(
        NoResourceFoundException ex,
        HttpServletRequest request
    ) {
        return buildResponse(
            HttpStatus.NOT_FOUND,
            "Requested resource was not found.",
            request,
            ex
        );
    }

    /**
     * Handles uncaught runtime exceptions.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(
        RuntimeException ex,
        HttpServletRequest request
    ) {
        return buildResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal server error.",
            request,
            ex
        );
    }

    /**
     * Final fallback for all uncaught exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(
        Exception ex,
        HttpServletRequest request
    ) {
        return buildResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal server error.",
            request,
            ex
        );
    }

    /**
     * Builds standardized error response and logs with trace id.
     */
    private ResponseEntity<Map<String, Object>> buildResponse(
        HttpStatus status,
        String message,
        HttpServletRequest request,
        Exception ex
    ) {
        String traceId = resolveTraceId(request);
        if (status.is5xxServerError()) {
            log.error(
                "API error traceId={} status={} path={} message={}",
                traceId,
                status.value(),
                request.getRequestURI(),
                ex.getMessage(),
                ex
            );
        } else {
            log.warn(
                "API error traceId={} status={} path={} message={}",
                traceId,
                status.value(),
                request.getRequestURI(),
                ex.getMessage()
            );
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", traceId);
        body.put("code", status.value());
        body.put("message", message);
        body.put("data", null);

        return ResponseEntity.status(status).body(body);
    }

    /**
     * Resolves trace id from request header or generates one.
     */
    private String resolveTraceId(HttpServletRequest request) {
        String headerTraceId = request.getHeader("X-Trace-Id");
        if (headerTraceId != null && !headerTraceId.isBlank()) {
            return headerTraceId.trim();
        }
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Formats field validation errors to readable message.
     */
    private String formatFieldError(FieldError fieldError) {
        String message = fieldError.getDefaultMessage();
        if (message == null || message.isBlank()) {
            message = "invalid value";
        }
        return fieldError.getField() + ": " + message;
    }
}
