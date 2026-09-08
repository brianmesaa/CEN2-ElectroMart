package com.electromart.domain;

/**
 * Stored result of a checkout request that used a given {@code Idempotency-Key}.
 *
 * @param key         idempotency key sent by the client
 * @param requestHash fingerprint of the request (user + normalized items) the key was first used with
 * @param orderId     identifier of the order created for the first request
 */
public record IdempotencyRecord(String key, String requestHash, String orderId) {
}
