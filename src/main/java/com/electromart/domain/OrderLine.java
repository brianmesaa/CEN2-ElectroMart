package com.electromart.domain;

/**
 * One line of a completed order. All monetary values are server calculated and in cents.
 */
public record OrderLine(
        String productId,
        String name,
        int quantity,
        long unitPriceCents,
        long lineTotalCents) {
}
