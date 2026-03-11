package com.mamoji.common.exception;

/**
 * Thrown when requested domain resource does not exist or is inaccessible.
 */
public class ResourceNotFoundException extends RuntimeException {

    /**
     * Creates exception with business-readable message.
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
