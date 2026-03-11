package com.mamoji.common.exception;

/**
 * Thrown when request parameters or payload are invalid.
 */
public class BadRequestException extends RuntimeException {

    /**
     * Creates exception with business-readable message.
     */
    public BadRequestException(String message) {
        super(message);
    }
}
