package com.electromart.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * One requested cart line. Only the product id and the quantity are accepted; any price sent
 * by the browser is ignored on purpose.
 */
public record CheckoutItemRequest(
        @NotBlank(message = "productId is required")
        String productId,

        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be a positive integer")
        Integer quantity) {
}
