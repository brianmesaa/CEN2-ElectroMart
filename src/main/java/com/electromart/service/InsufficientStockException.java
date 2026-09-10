package com.electromart.service;

import java.util.List;

/**
 * At least one requested product does not have enough stock. Mapped to HTTP 409.
 * Nothing is reserved or written: the order is not created and inventory is unchanged.
 */
public class InsufficientStockException extends RuntimeException {

    /** One shortage entry: what was asked for and what is actually available. */
    public record Shortage(String productId, String name, int requested, int available) {
    }

    private final List<Shortage> shortages;

    public InsufficientStockException(String message, List<Shortage> shortages) {
        super(message);
        this.shortages = List.copyOf(shortages);
    }

    public List<Shortage> getShortages() {
        return shortages;
    }
}
