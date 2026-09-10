package com.electromart.service;

import java.util.List;

/**
 * The request is malformed or references data that cannot exist (unknown product,
 * non positive quantity, missing user, ...). Mapped to HTTP 400.
 */
public class ValidationException extends RuntimeException {

    private final List<String> details;

    public ValidationException(String message) {
        this(message, List.of());
    }

    public ValidationException(String message, List<String> details) {
        super(message);
        this.details = List.copyOf(details);
    }

    public List<String> getDetails() {
        return details;
    }
}
