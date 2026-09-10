package com.electromart.persistence;

/**
 * Raised when the state file cannot be read, parsed, validated or written.
 *
 * <p>An invalid existing file makes the application fail fast instead of silently
 * replacing (and therefore losing) the stored data.</p>
 */
public class StateFileException extends RuntimeException {

    public StateFileException(String message) {
        super(message);
    }

    public StateFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
