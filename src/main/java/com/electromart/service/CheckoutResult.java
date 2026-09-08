package com.electromart.service;

import com.electromart.domain.Order;

/**
 * Result of a checkout call.
 *
 * @param order  the created (or, for a replay, the previously created) order
 * @param replay {@code true} when the idempotency key had already been used with the very same
 *               request, meaning no new order was created and no stock was taken
 */
public record CheckoutResult(Order order, boolean replay) {
}
