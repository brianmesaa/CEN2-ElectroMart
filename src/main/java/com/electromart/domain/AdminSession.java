package com.electromart.domain;

import java.time.Instant;

/**
 * Active admin session record held in memory only.
 */
public record AdminSession(String token, String email, Instant createdAt) {
}
