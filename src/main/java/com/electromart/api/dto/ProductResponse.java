package com.electromart.api.dto;

import com.electromart.domain.Product;

/**
 * Catalog entry as exposed by {@code GET /api/products}, including the current stock.
 */
public record ProductResponse(String id, String name, long priceCents, int stock) {

    public static ProductResponse of(Product product, int stock) {
        return new ProductResponse(product.id(), product.name(), product.priceCents(), stock);
    }
}
