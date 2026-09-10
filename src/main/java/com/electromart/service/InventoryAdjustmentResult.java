package com.electromart.service;

/**
 * Result of adding stock to a catalog product.
 */
public record InventoryAdjustmentResult(
        String productId,
        String productName,
        int quantityAdded,
        int resultingStock,
        boolean replay
) {
}
