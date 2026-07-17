package com.mamoji.platform.web;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed");
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
            fields.putIfAbsent(error.getField(), error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage())
        );
        problem.setProperty("fields", fields);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraintViolation(ConstraintViolationException exception) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "constraint_violation", "Request constraint failed");
        problem.setProperty("violations", exception.getConstraintViolations().stream()
            .map(violation -> Map.of(
                "path", violation.getPropertyPath().toString(),
                "message", violation.getMessage()
            ))
            .toList());
        return problem;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail concurrentModification(OptimisticLockingFailureException exception) {
        return problem(HttpStatus.CONFLICT, "concurrent_modification", "The record changed; refresh and retry");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicateKey(DuplicateKeyException exception) {
        return problem(HttpStatus.CONFLICT, "duplicate_record", "A record with the same unique key already exists");
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create("https://mamoji.local/problems/" + code));
        problem.setProperty("code", code);
        return problem;
    }
}
