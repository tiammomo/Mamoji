package com.mamoji.common.exception;

/**
 * Thrown when current user attempts an operation without sufficient permission.
 */
public class ForbiddenOperationException extends RuntimeException {

    /**
     * Creates exception with business-readable message.
     */
    public ForbiddenOperationException(String message) {
        super(message);
    }
}
