package com.electromart.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record InventoryAdjustmentRequest(
        @JsonAlias({"productID", "productId"})
        String productId,
        Long quantity
) {
}
