package com.electromart.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Body of {@code POST /api/checkout}: the buyer and the requested products.
 * Card details are never part of this payload.
 */
public record CheckoutRequest(
        @NotBlank(message = "user is required")
        @Email(message = "user must be a valid e-mail address")
        String user,

        @NotEmpty(message = "items must contain at least one product")
        @Valid
        List<CheckoutItemRequest> items) {
}
