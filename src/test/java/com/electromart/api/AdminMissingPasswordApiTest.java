package com.electromart.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that without an admin password configured, the application starts normally
 * but any login attempt returns HTTP 503 with a useful message.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminMissingPasswordApiTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("electromart.data-file", () -> tempDir.resolve("state.json").toString());
        // Ensure no admin password is set
        registry.add("electromart.admin-password", () -> "");
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("the public store starts normally when no admin password is configured")
    void publicStoreStartsNormally() {
        ResponseEntity<JsonNode> products = rest.getForEntity("/api/products", JsonNode.class);
        assertThat(products.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("attempting to log in without an admin password configured returns 503")
    void loginReturns503WhenAdminPasswordNotConfigured() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(
                "{\"email\":\"bmesa@gmail.com\",\"password\":\"somepassword\"}", headers);

        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/admin/login", HttpMethod.POST, request, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message").asText())
                .containsIgnoringCase("admin access has not been configured");
    }
}
