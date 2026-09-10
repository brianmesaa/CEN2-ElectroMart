package com.electromart.api;

import com.electromart.api.dto.AdminLoginRequest;
import com.electromart.api.dto.AdminSessionResponse;
import com.electromart.api.dto.InventoryAdjustmentRequest;
import com.electromart.api.dto.InventoryAdjustmentResponse;
import com.electromart.config.ElectroMartProperties;
import com.electromart.domain.AdminSession;
import com.electromart.service.AdminNotConfiguredException;
import com.electromart.service.AdminSessionService;
import com.electromart.service.AdminUnauthorizedException;
import com.electromart.service.InventoryAdjustmentResult;
import com.electromart.service.InventoryAdjustmentService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Administrative API:
 * <ul>
 *   <li>POST /api/admin/login: authenticates the admin and issues an HttpOnly session cookie</li>
 *   <li>GET /api/admin/session: returns the active admin email or 401</li>
 *   <li>POST /api/admin/logout: invalidates the session and clears the cookie (204)</li>
 *   <li>POST /api/admin/inventory-adjustments: adds stock to a product (requires session and Idempotency-Key)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public static final String SESSION_COOKIE_NAME = "admin_session";
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final ElectroMartProperties properties;
    private final AdminSessionService sessionService;
    private final InventoryAdjustmentService adjustmentService;

    public AdminController(ElectroMartProperties properties,
                           AdminSessionService sessionService,
                           InventoryAdjustmentService adjustmentService) {
        this.properties = properties;
        this.sessionService = sessionService;
        this.adjustmentService = adjustmentService;
    }

    @PostMapping(value = "/login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminSessionResponse> login(@RequestBody(required = false) AdminLoginRequest request,
                                                      HttpServletRequest httpRequest,
                                                      HttpServletResponse httpResponse) {
        if (!properties.hasAdminPassword()) {
            throw new AdminNotConfiguredException("Admin access has not been configured.");
        }

        if (request == null
                || request.email() == null || request.email().isBlank()
                || request.password() == null || request.password().isBlank()) {
            throw new AdminUnauthorizedException("Invalid email or password.");
        }

        String expectedUser = properties.getAdminUser() != null ? properties.getAdminUser().trim() : "";
        String providedUser = request.email().trim();
        String expectedPassword = properties.getAdminPassword();
        String providedPassword = request.password();

        if (!expectedUser.equalsIgnoreCase(providedUser) || !expectedPassword.equals(providedPassword)) {
            throw new AdminUnauthorizedException("Invalid email or password.");
        }

        AdminSession session = sessionService.createSession(expectedUser);
        boolean isSecure = httpRequest.isSecure()
                || "https".equalsIgnoreCase(httpRequest.getHeader("X-Forwarded-Proto"));

        ResponseCookie cookie = ResponseCookie.from(SESSION_COOKIE_NAME, session.token())
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .secure(isSecure)
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return ResponseEntity.ok(new AdminSessionResponse(session.email()));
    }

    @GetMapping(value = "/session", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminSessionResponse> session(HttpServletRequest httpRequest) {
        AdminSession session = requireSession(httpRequest);
        return ResponseEntity.ok(new AdminSessionResponse(session.email()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String token = extractSessionToken(httpRequest);
        if (token != null) {
            sessionService.invalidateSession(token);
        }

        boolean isSecure = httpRequest.isSecure()
                || "https".equalsIgnoreCase(httpRequest.getHeader("X-Forwarded-Proto"));

        ResponseCookie clearCookie1 = ResponseCookie.from(SESSION_COOKIE_NAME, "")
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .secure(isSecure)
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, clearCookie1.toString());

        ResponseCookie clearCookie2 = ResponseCookie.from("ELECTROMART_ADMIN_SESSION", "")
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .secure(isSecure)
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, clearCookie2.toString());

        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/inventory-adjustments",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InventoryAdjustmentResponse> adjustInventory(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) InventoryAdjustmentRequest request,
            HttpServletRequest httpRequest) {

        requireSession(httpRequest);

        String prodId = request != null ? request.productId() : null;
        Long qty = request != null ? request.quantity() : null;

        InventoryAdjustmentResult result = adjustmentService.adjustStock(idempotencyKey, prodId, qty);

        InventoryAdjustmentResponse response = new InventoryAdjustmentResponse(
                result.productId(),
                result.productName(),
                result.quantityAdded(),
                result.resultingStock()
        );

        return ResponseEntity.ok()
                .header(IDEMPOTENCY_HEADER, idempotencyKey == null ? "" : idempotencyKey.trim())
                .body(response);
    }

    private AdminSession requireSession(HttpServletRequest httpRequest) {
        String token = extractSessionToken(httpRequest);
        if (token == null) {
            throw new AdminUnauthorizedException("Admin authentication required.");
        }
        Optional<AdminSession> session = sessionService.findSession(token);
        if (session.isEmpty()) {
            throw new AdminUnauthorizedException("Admin authentication required.");
        }
        return session.get();
    }

    private String extractSessionToken(HttpServletRequest httpRequest) {
        if (httpRequest.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : httpRequest.getCookies()) {
            if (SESSION_COOKIE_NAME.equalsIgnoreCase(cookie.getName())
                    || "ELECTROMART_ADMIN_SESSION".equalsIgnoreCase(cookie.getName())) {
                return cookie.getValue();
            }
        }
        for (Cookie cookie : httpRequest.getCookies()) {
            if (sessionService.isValidToken(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
