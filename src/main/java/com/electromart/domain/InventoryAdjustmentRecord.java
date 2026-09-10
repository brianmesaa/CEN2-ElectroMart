package com.electromart.domain;

/**
 * Stored result of an inventory adjustment request that used a given {@code Idempotency-Key}.
 *
 * @param key            idempotency key sent by the client
 * @param productId      identifier of the product adjusted
 * @param productName    name of the product at the time of adjustment
 * @param quantityAdded  positive whole units added
 * @param resultingStock resulting stock after the addition
 */
public record InventoryAdjustmentRecord(
        String key,
        String productId,
        String productName,
        int quantityAdded,
        int resultingStock) {
}
