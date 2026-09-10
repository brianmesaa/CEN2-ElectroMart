package com.electromart.service;

/**
 * Thrown when an inventory addition would make a product's stock greater than {@link Integer#MAX_VALUE}.
 */
public class StockOverflowException extends RuntimeException {
    public StockOverflowException(String message) {
        super(message);
    }
}
