package com.electromart.service;

/**
 * The supplied {@code Idempotency-Key} was already used for a different user or cart.
 * Mapped to HTTP 409.
 */
public class IdempotencyConflictException extends RuntimeException {

    private final String key;

    public IdempotencyConflictException(String key, String message) {
        super(message);
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
