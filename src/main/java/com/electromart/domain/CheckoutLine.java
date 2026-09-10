package com.electromart.domain;

/**
 * A validated and normalized checkout line (product id + positive quantity).
 */
public record CheckoutLine(String productId, int quantity) {
}
