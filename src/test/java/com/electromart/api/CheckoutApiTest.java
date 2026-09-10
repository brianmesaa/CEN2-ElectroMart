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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end tests of the HTTP API (status codes, payloads, headers).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.MethodName.class)
class CheckoutApiTest {

    @TempDir
    static Path tempDir;

    static Path stateFile;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        stateFile = tempDir.resolve("api-state.json");
        registry.add("electromart.data-file", () -> stateFile.toString());
    }

    @Autowired
    TestRestTemplate rest;

    private ResponseEntity<JsonNode> checkout(String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.add("Idempotency-Key", key);
        }
        return rest.exchange("/api/checkout", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static String cart(String user, String productId, int quantity) {
        return "{\"user\":\"" + user + "\",\"items\":[{\"productId\":\"" + productId
                + "\",\"quantity\":" + quantity + "}]}";
    }

    @Test
    @DisplayName("GET /api/products returns the catalog with prices in cents and stock")
    void productsEndpoint() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/products", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(16);
        JsonNode first = body.get(0);
        assertThat(first.has("id")).isTrue();
        assertThat(first.has("name")).isTrue();
        assertThat(first.get("priceCents").isInt()).isTrue();
        assertThat(first.get("stock").isInt()).isTrue();
    }

    @Test
    @DisplayName("POST /api/checkout creates one order and returns 201")
    void checkoutCreatesOrder() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> response = checkout(key,
                "{\"user\":\"api@example.com\",\"items\":[{\"productId\":\"smartwatch\",\"quantity\":2},"
                        + "{\"productId\":\"smartwatch\",\"quantity\":1}]}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode order = response.getBody();
        assertThat(order.get("id").asText()).isNotBlank();
        assertThat(order.get("user").asText()).isEqualTo("api@example.com");
        assertThat(order.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        assertThat(order.get("items")).hasSize(1);
        assertThat(order.get("items").get(0).get("quantity").asInt()).isEqualTo(3);
        assertThat(order.get("items").get(0).get("unitPriceCents").asLong()).isEqualTo(15000);
        assertThat(order.get("items").get(0).get("lineTotalCents").asLong()).isEqualTo(45000);
        assertThat(order.get("totalCents").asLong()).isEqualTo(45000);
    }

    @Test
    @DisplayName("the same key with the same cart replays the original order (200)")
    void checkoutIsIdempotent() {
        String key = UUID.randomUUID().toString();
        String body = cart("idem@example.com", "bluetooth-speaker", 1);

        ResponseEntity<JsonNode> first = checkout(key, body);
        ResponseEntity<JsonNode> second = checkout(key, body);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody());
    }

    @Test
    @DisplayName("reusing a key for a different cart or user returns 409")
    void keyReuseReturnsConflict() {
        String key = UUID.randomUUID().toString();
        checkout(key, cart("reuse@example.com", "bluetooth-speaker", 1));

        ResponseEntity<JsonNode> otherCart = checkout(key, cart("reuse@example.com", "bluetooth-speaker", 2));
        ResponseEntity<JsonNode> otherUser = checkout(key, cart("someone@example.com", "bluetooth-speaker", 1));

        assertThat(otherCart.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(otherCart.getBody().get("error").asText()).isEqualTo("idempotency_key_reuse");
        assertThat(otherUser.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("a missing Idempotency-Key header returns 400")
    void missingIdempotencyKeyReturnsBadRequest() {
        ResponseEntity<JsonNode> response = checkout(null, cart("nokey@example.com", "smartwatch", 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message").asText()).contains("Idempotency-Key");
    }

    @Test
    @DisplayName("unknown products and invalid quantities return 400")
    void invalidRequestsReturnBadRequest() {
        assertThat(checkout(UUID.randomUUID().toString(), cart("x@example.com", "does-not-exist", 1))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(checkout(UUID.randomUUID().toString(), cart("x@example.com", "smartwatch", 0))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(checkout(UUID.randomUUID().toString(), cart("x@example.com", "smartwatch", -2))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(checkout(UUID.randomUUID().toString(),
                "{\"user\":\"x@example.com\",\"items\":[{\"productId\":\"smartwatch\",\"quantity\":1.5}]}")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(checkout(UUID.randomUUID().toString(), "{\"user\":\"x@example.com\",\"items\":[]}")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(checkout(UUID.randomUUID().toString(), "{\"user\":\"not-an-email\",\"items\":"
                + "[{\"productId\":\"smartwatch\",\"quantity\":1}]}")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(checkout(UUID.randomUUID().toString(), "{ broken json")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("insufficient stock returns 409 and does not create an order")
    void insufficientStockReturnsConflict() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> response = checkout(key, cart("stock@example.com", "sony-bravia-9-mini-led", 999));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error").asText()).isEqualTo("insufficient_stock");

        ResponseEntity<JsonNode> orders = rest.getForEntity("/api/orders?user=stock@example.com", JsonNode.class);
        assertThat(orders.getBody()).isEmpty();
    }

    @Test
    @DisplayName("prices sent by the browser are ignored, card data is never accepted or stored")
    void ignoresClientSuppliedPricesAndCardData() throws IOException {
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> response = checkout(key,
                "{\"user\":\"card@example.com\",\"cardNumber\":\"4111111111111111\",\"cardCVC\":\"123\","
                        + "\"items\":[{\"productId\":\"smartwatch\",\"quantity\":1,\"priceCents\":1,\"price\":\"1\"}]}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("totalCents").asLong()).isEqualTo(15000);

        String persisted = Files.readString(stateFile);
        assertThat(persisted).doesNotContain("4111111111111111").doesNotContain("cardCVC");
    }

    @Test
    @DisplayName("GET /api/orders filters by user and the prototype admin sees everything")
    void ordersEndpointFiltersByUser() {
        checkout(UUID.randomUUID().toString(), cart("owner@example.com", "smartwatch", 1));
        checkout(UUID.randomUUID().toString(), cart("other@example.com", "smartwatch", 1));

        ResponseEntity<JsonNode> owner = rest.getForEntity("/api/orders?user=owner@example.com", JsonNode.class);
        assertThat(owner.getStatusCode()).isEqualTo(HttpStatus.OK);
        owner.getBody().forEach(order -> assertThat(order.get("user").asText()).isEqualTo("owner@example.com"));
        assertThat(owner.getBody()).isNotEmpty();

        ResponseEntity<JsonNode> admin = rest.getForEntity("/api/orders?user=bmesa@gmail.com", JsonNode.class);
        assertThat(admin.getBody().size()).isGreaterThan(owner.getBody().size());

        assertThat(rest.getForEntity("/api/orders", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("the frontend is served from the same origin as the API")
    void servesTheStaticFrontend() {
        assertThat(rest.getForEntity("/index.html", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/shoppingCart.html", String.class).getBody()).contains("confirmPaymentBtn");
        assertThat(rest.getForEntity("/app.js", String.class).getBody()).contains("/api/checkout");
        assertThat(rest.getForEntity("/style.css", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
