package com.electromart.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.junit.jupiter.api.BeforeEach;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.MethodName.class)
class AdminApiTest {

    static final String ADMIN_USER = "bmesa@gmail.com";
    static final String ADMIN_PASSWORD = "super-secret-admin-pass";

    @TempDir
    static Path tempDir;

    static Path stateFile;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        stateFile = tempDir.resolve("admin-test-state.json");
        registry.add("electromart.data-file", () -> stateFile.toString());
        registry.add("electromart.admin-user", () -> ADMIN_USER);
        registry.add("electromart.admin-password", () -> ADMIN_PASSWORD);
    }

    @Autowired
    TestRestTemplate rest;

    @BeforeEach
    void setupRestTemplate() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }


    private String loginAndGetCookie() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"email\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}";

        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/admin/login", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotEmpty();
        return cookies.get(0);
    }

    private ResponseEntity<JsonNode> adjust(String cookie, String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        if (key != null) {
            headers.add("Idempotency-Key", key);
        }
        return rest.exchange("/api/admin/inventory-adjustments",
                HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    @Test
    @DisplayName("successful login creates a server-side session with an HttpOnly SameSite=Strict cookie")
    void successfulLogin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"email\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}";

        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/admin/login", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = response.getBody();
        assertThat(json).isNotNull();
        assertThat(json.get("email").asText()).isEqualTo(ADMIN_USER);

        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotEmpty();
        String cookieHeader = cookies.get(0);
        assertThat(cookieHeader).contains("HttpOnly");
        assertThat(cookieHeader).contains("SameSite=Strict");
        assertThat(cookieHeader).contains("Path=/");
    }

    @Test
    @DisplayName("login with wrong password returns 401 with generic error")
    void failedLoginWrongPassword() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"email\":\"" + ADMIN_USER + "\",\"password\":\"wrong-pass\"}";

        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/admin/login", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("message").asText()).isEqualTo("Invalid email or password.");
    }

    @Test
    @DisplayName("login with wrong email returns same 401 without revealing which was wrong")
    void failedLoginWrongEmail() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"email\":\"wrong@example.com\",\"password\":\"" + ADMIN_PASSWORD + "\"}";

        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/admin/login", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("message").asText()).isEqualTo("Invalid email or password.");
    }

    @Test
    @DisplayName("GET /api/admin/session returns the logged in admin email or 401")
    void sessionEndpoint() {
        // Without cookie -> 401
        ResponseEntity<JsonNode> unauth = rest.getForEntity("/api/admin/session", JsonNode.class);
        assertThat(unauth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // With valid session cookie -> 200 with email
        String cookie = loginAndGetCookie();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);

        ResponseEntity<JsonNode> auth = rest.exchange(
                "/api/admin/session", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(auth.getBody().get("email").asText()).isEqualTo(ADMIN_USER);
    }

    @Test
    @DisplayName("POST /api/admin/logout invalidates session and clears cookie")
    void logoutEndpoint() {
        String cookie = loginAndGetCookie();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);

        ResponseEntity<Void> logoutResponse = rest.exchange(
                "/api/admin/logout", HttpMethod.POST, new HttpEntity<>(headers), Void.class);
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Cookie is cleared
        List<String> setCookies = logoutResponse.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotEmpty();
        assertThat(setCookies.get(0)).contains("Max-Age=0");

        // Subsequent session request with the old cookie returns 401
        ResponseEntity<JsonNode> checkSession = rest.exchange(
                "/api/admin/session", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(checkSession.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("unauthorized inventory adjustment returns 401")
    void unauthorizedAdjustment() {
        String key = UUID.randomUUID().toString();
        String body = "{\"productId\":\"smartwatch\",\"quantity\":5}";

        // No cookie
        ResponseEntity<JsonNode> response = adjust(null, key, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Fake cookie
        ResponseEntity<JsonNode> fakeCookieResponse = adjust("admin_session=invalid-token", key, body);
        assertThat(fakeCookieResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("inventory adjustment requires Idempotency-Key header")
    void missingIdempotencyKeyReturns400() {
        String cookie = loginAndGetCookie();
        String body = "{\"productId\":\"smartwatch\",\"quantity\":5}";

        ResponseEntity<JsonNode> response = adjust(cookie, null, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message").asText()).contains("Idempotency-Key");
    }

    @Test
    @DisplayName("invalid quantities return 400 and leave stock unchanged")
    void invalidQuantitiesReturn400() {
        String cookie = loginAndGetCookie();

        // 0 quantity
        assertThat(adjust(cookie, UUID.randomUUID().toString(),
                "{\"productId\":\"smartwatch\",\"quantity\":0}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // negative quantity
        assertThat(adjust(cookie, UUID.randomUUID().toString(),
                "{\"productId\":\"smartwatch\",\"quantity\":-5}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // decimal quantity
        assertThat(adjust(cookie, UUID.randomUUID().toString(),
                "{\"productId\":\"smartwatch\",\"quantity\":2.5}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // missing quantity
        assertThat(adjust(cookie, UUID.randomUUID().toString(),
                "{\"productId\":\"smartwatch\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // malformed body
        assertThat(adjust(cookie, UUID.randomUUID().toString(),
                "{ broken json").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("unknown or missing product returns 400 and leaves stock unchanged")
    void unknownProductReturns400() {
        String cookie = loginAndGetCookie();

        assertThat(adjust(cookie, UUID.randomUUID().toString(),
                "{\"productId\":\"non-existent-product\",\"quantity\":5}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(adjust(cookie, UUID.randomUUID().toString(),
                "{\"productId\":\"\",\"quantity\":5}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(adjust(cookie, UUID.randomUUID().toString(),
                "{\"quantity\":5}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("stock overflow exceeding Integer.MAX_VALUE returns 409 and leaves stock unchanged")
    void stockOverflowReturns409() {
        String cookie = loginAndGetCookie();
        String key = UUID.randomUUID().toString();

        // quantity that overflows Integer.MAX_VALUE
        ResponseEntity<JsonNode> response = adjust(cookie, key,
                "{\"productId\":\"smartwatch\",\"quantity\":" + (Long.MAX_VALUE - 10) + "}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error").asText()).isEqualTo("stock_overflow");
    }

    @Test
    @DisplayName("successful stock addition returns 200 with updated stock and updates GET /api/products")
    void successfulAdjustmentUpdatesStock() {
        String cookie = loginAndGetCookie();
        String key = UUID.randomUUID().toString();

        // Initial stock check
        ResponseEntity<JsonNode> productsBefore = rest.getForEntity("/api/products", JsonNode.class);
        int initialStock = 0;
        for (JsonNode p : productsBefore.getBody()) {
            if ("soundbar".equals(p.get("id").asText())) {
                initialStock = p.get("stock").asInt();
            }
        }

        // Add 10 units
        ResponseEntity<JsonNode> response = adjust(cookie, key,
                "{\"productId\":\"soundbar\",\"quantity\":10}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("productId").asText()).isEqualTo("soundbar");
        assertThat(body.get("productName").asText()).isEqualTo("Soundbar");
        assertThat(body.get("quantityAdded").asInt()).isEqualTo(10);
        assertThat(body.get("resultingStock").asInt()).isEqualTo(initialStock + 10);

        // Verify GET /api/products returns the updated stock
        ResponseEntity<JsonNode> productsAfter = rest.getForEntity("/api/products", JsonNode.class);
        int updatedStock = 0;
        for (JsonNode p : productsAfter.getBody()) {
            if ("soundbar".equals(p.get("id").asText())) {
                updatedStock = p.get("stock").asInt();
            }
        }
        assertThat(updatedStock).isEqualTo(initialStock + 10);
    }

    @Test
    @DisplayName("idempotent retries return the original result without adding stock a second time")
    void idempotencyRetriesReturnOriginalResult() {
        String cookie = loginAndGetCookie();
        String key = UUID.randomUUID().toString();
        String body = "{\"productId\":\"bluetooth-speaker\",\"quantity\":7}";

        ResponseEntity<JsonNode> first = adjust(cookie, key, body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        int resultingStock = first.getBody().get("resultingStock").asInt();

        // Retry with exact same key and body
        ResponseEntity<JsonNode> retry = adjust(cookie, key, body);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().get("resultingStock").asInt()).isEqualTo(resultingStock);
        assertThat(retry.getBody().get("quantityAdded").asInt()).isEqualTo(7);

        // Verify stock was not added twice
        ResponseEntity<JsonNode> products = rest.getForEntity("/api/products", JsonNode.class);
        for (JsonNode p : products.getBody()) {
            if ("bluetooth-speaker".equals(p.get("id").asText())) {
                assertThat(p.get("stock").asInt()).isEqualTo(resultingStock);
            }
        }
    }

    @Test
    @DisplayName("reusing an idempotency key with different product or quantity returns 409")
    void idempotencyKeyReuseConflict() {
        String cookie = loginAndGetCookie();
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = adjust(cookie, key,
                "{\"productId\":\"smartwatch\",\"quantity\":3}");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Reuse with different quantity
        ResponseEntity<JsonNode> conflictQty = adjust(cookie, key,
                "{\"productId\":\"smartwatch\",\"quantity\":8}");
        assertThat(conflictQty.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Reuse with different product
        ResponseEntity<JsonNode> conflictProd = adjust(cookie, key,
                "{\"productId\":\"oled-tv\",\"quantity\":3}");
        assertThat(conflictProd.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("checkout and inventory adjustment have separate idempotency namespaces")
    void separateIdempotencyNamespaces() {
        String cookie = loginAndGetCookie();
        String sharedKey = "shared-namespace-key-" + UUID.randomUUID();

        // 1. Adjustment with sharedKey succeeds
        ResponseEntity<JsonNode> adjResponse = adjust(cookie, sharedKey,
                "{\"productId\":\"4k-tv\",\"quantity\":2}");
        assertThat(adjResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 2. Checkout with the EXACT SAME sharedKey also succeeds without conflict
        HttpHeaders checkoutHeaders = new HttpHeaders();
        checkoutHeaders.setContentType(MediaType.APPLICATION_JSON);
        checkoutHeaders.add("Idempotency-Key", sharedKey);
        String cart = "{\"user\":\"namespace@example.com\",\"items\":[{\"productId\":\"4k-tv\",\"quantity\":1}]}";

        ResponseEntity<JsonNode> checkoutResponse = rest.exchange(
                "/api/checkout", HttpMethod.POST, new HttpEntity<>(cart, checkoutHeaders), JsonNode.class);
        assertThat(checkoutResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("admin.html is served from the static frontend")
    void servesAdminHtml() {
        ResponseEntity<String> response = rest.getForEntity("/admin.html", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Admin Login");
        assertThat(response.getBody()).contains("Inventory Management");
        assertThat(response.getBody()).contains("adminLogoutBtn");
    }
}
