package com.electromart.api;

import com.electromart.api.dto.ErrorResponse;
import com.electromart.persistence.StateFileException;
import com.electromart.service.AdminNotConfiguredException;
import com.electromart.service.AdminUnauthorizedException;
import com.electromart.service.IdempotencyConflictException;
import com.electromart.service.InsufficientStockException;
import com.electromart.service.StockOverflowException;
import com.electromart.service.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Translates domain and framework exceptions into the uniform JSON error payload.
 *
 * <ul>
 *   <li>400 - malformed body, missing header/parameter, unknown product, invalid quantity</li>
 *   <li>401 - unauthorized admin access or invalid admin credentials</li>
 *   <li>409 - insufficient stock, idempotency key reused for another cart/adjustment, stock overflow</li>
 *   <li>500 - unexpected problems (including a broken state file)</li>
 *   <li>503 - admin password not configured</li>
 * </ul>
 */
@RestControllerAdvice(assignableTypes = {
        ProductController.class,
        CheckoutController.class,
        OrderController.class,
        AdminController.class
})
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AdminNotConfiguredException.class)
    public ResponseEntity<ErrorResponse> handleAdminNotConfigured(AdminNotConfiguredException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of("admin_not_configured", e.getMessage()));
    }

    @ExceptionHandler(AdminUnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleAdminUnauthorized(AdminUnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("unauthorized", e.getMessage()));
    }

    @ExceptionHandler(StockOverflowException.class)
    public ResponseEntity<ErrorResponse> handleStockOverflow(StockOverflowException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("stock_overflow", e.getMessage()));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("invalid_request", e.getMessage(), e.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        List<String> global = e.getBindingResult().getGlobalErrors().stream()
                .map(error -> error.getObjectName() + ": " + error.getDefaultMessage())
                .toList();
        List<String> all = java.util.stream.Stream.concat(details.stream(), global.stream()).toList();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("invalid_request", "The request is invalid.",
                        all.isEmpty() ? List.of("The request is invalid.") : all));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("invalid_request", "The " + e.getHeaderName() + " header is required."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("invalid_request",
                        "The '" + e.getParameterName() + "' query parameter is required."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        String uri = request != null ? request.getRequestURI() : "";
        String message;
        if (uri.startsWith("/api/checkout")) {
            message = "The request body is not valid JSON for a checkout "
                    + "(expected {\"user\":\"...\",\"items\":[{\"productId\":\"...\",\"quantity\":1}]}).";
        } else {
            message = "The request body is not valid JSON or contains invalid values.";
        }
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("invalid_request", message));
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> handleStock(InsufficientStockException e) {
        List<String> details = e.getShortages().stream()
                .map(s -> s.productId() + ": requested " + s.requested() + ", available " + s.available())
                .toList();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("insufficient_stock", e.getMessage(), details, e.getShortages()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotency(IdempotencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("idempotency_key_reuse", e.getMessage()));
    }

    @ExceptionHandler(StateFileException.class)
    public ResponseEntity<ErrorResponse> handleStateFile(StateFileException e) {
        log.error("State file problem", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("storage_error",
                        "The update could not be stored. No inventory was changed."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected error while handling an API request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("internal_error", "Unexpected server error."));
    }
}
