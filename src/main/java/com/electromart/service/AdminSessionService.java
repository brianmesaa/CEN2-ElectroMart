package com.electromart.service;

import com.electromart.domain.AdminSession;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory manager for active administrator sessions.
 *
 * <p>Sessions are never written to the JSON state file and survive only in memory.</p>
 */
@Service
public class AdminSessionService {

    private final Map<String, AdminSession> sessions = new ConcurrentHashMap<>();

    public AdminSession createSession(String email) {
        String token = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        AdminSession session = new AdminSession(token, email, Instant.now());
        sessions.put(token, session);
        return session;
    }

    public Optional<AdminSession> findSession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(token));
    }

    public boolean isValidToken(String token) {
        return token != null && !token.isBlank() && sessions.containsKey(token);
    }

    public void invalidateSession(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    public void clearAll() {
        sessions.clear();
    }
}
