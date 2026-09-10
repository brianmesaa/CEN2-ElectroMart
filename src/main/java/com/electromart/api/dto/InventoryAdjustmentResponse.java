package com.electromart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record InventoryAdjustmentResponse(
        @JsonProperty("productId") String productId,
        @JsonProperty("productName") String productName,
        @JsonProperty("quantityAdded") int quantityAdded,
        @JsonProperty("resultingStock") int resultingStock
) {
    @JsonProperty("productID")
    public String getProductID() {
        return productId;
    }

    @JsonProperty("name")
    public String getName() {
        return productName;
    }

    @JsonProperty("quantity")
    public int getQuantity() {
        return quantityAdded;
    }

    @JsonProperty("stock")
    public int getStock() {
        return resultingStock;
    }
}
