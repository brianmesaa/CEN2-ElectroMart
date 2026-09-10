package com.electromart.service;

/**
 * Thrown when admin credentials or session cookie are invalid or missing.
 */
public class AdminUnauthorizedException extends RuntimeException {
    public AdminUnauthorizedException(String message) {
        super(message);
    }
}
