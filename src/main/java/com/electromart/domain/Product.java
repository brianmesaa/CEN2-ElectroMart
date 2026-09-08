package com.electromart.domain;

/**
 * A product of the server side catalog.
 *
 * @param id         stable product identifier used by the frontend and the checkout API
 * @param name       display name
 * @param priceCents price in cents (integer arithmetic only, never floating point)
 * @param stock      initial stock, used when a fresh state file is created
 */
public record Product(String id, String name, long priceCents, int stock) {
}
