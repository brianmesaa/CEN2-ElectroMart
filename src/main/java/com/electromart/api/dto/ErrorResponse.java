package com.electromart.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Uniform error payload: a short machine readable code, a human readable message and
 * optional details (field errors, stock shortages, ...).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(String error, String message, List<String> details, Object data) {

    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, List.of(), null);
    }

    public static ErrorResponse of(String error, String message, List<String> details) {
        return new ErrorResponse(error, message, details, null);
    }

    public static ErrorResponse of(String error, String message, List<String> details, Object data) {
        return new ErrorResponse(error, message, details, data);
    }
}
