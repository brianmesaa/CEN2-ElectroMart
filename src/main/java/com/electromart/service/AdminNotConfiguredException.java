package com.electromart.service;

/**
 * Thrown when an admin login attempt is made but no admin password has been configured.
 */
public class AdminNotConfiguredException extends RuntimeException {
    public AdminNotConfiguredException(String message) {
        super(message);
    }
}
