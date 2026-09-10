package com.electromart.api;

import com.electromart.api.dto.CheckoutItemRequest;
import com.electromart.api.dto.CheckoutRequest;
import com.electromart.domain.CheckoutLine;
import com.electromart.domain.Order;
import com.electromart.service.CheckoutResult;
import com.electromart.service.CheckoutService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code POST /api/checkout} - creates one order from a user e-mail and a list of
 * product ids with quantities. Requires an {@code Idempotency-Key} header.
 */
@RestController
@RequestMapping("/api")
public class CheckoutController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping(value = "/checkout",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Order> checkout(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request) {

        List<CheckoutLine> lines = request.items() == null ? List.of() : request.items().stream()
                .map(CheckoutController::toLine)
                .toList();

        CheckoutResult result = checkoutService.checkout(idempotencyKey, request.user(), lines);

        // A replayed request returns the original order (200), a new one is created (201).
        HttpStatus status = result.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .header(IDEMPOTENCY_HEADER, idempotencyKey == null ? "" : idempotencyKey.trim())
                .body(result.order());
    }

    private static CheckoutLine toLine(CheckoutItemRequest item) {
        if (item == null) {
            return new CheckoutLine(null, 0);
        }
        return new CheckoutLine(item.productId(), item.quantity() == null ? 0 : item.quantity());
    }
}
