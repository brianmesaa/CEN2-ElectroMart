package com.electromart.domain;

import java.util.List;

/**
 * A completed order.
 *
 * @param id         unique order identifier
 * @param user       e-mail address of the buyer
 * @param createdAt  ISO-8601 creation timestamp (UTC)
 * @param items      order lines
 * @param totalCents total price of the order in cents
 */
public record Order(
        String id,
        String user,
        String createdAt,
        List<OrderLine> items,
        long totalCents) {
}
