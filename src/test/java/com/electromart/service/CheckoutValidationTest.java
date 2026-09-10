package com.electromart.service;

import com.electromart.domain.CheckoutLine;
import com.electromart.persistence.PersistentState;
import com.electromart.persistence.StateRepository;
import com.electromart.support.TestState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checkout request validation: everything is checked before any state is touched.
 */
class CheckoutValidationTest {

    @TempDir
    Path tempDir;

    private StateRepository repository;
    private CheckoutService checkout;
    private PersistentState before;

    @BeforeEach
    void setUp() {
        repository = TestState.repository(tempDir.resolve("state.json"));
        checkout = TestState.checkoutService(repository);
        before = repository.current();
    }

    private void assertNothingChanged() {
        assertThat(repository.current().orders()).isEmpty();
        assertThat(repository.current().inventory()).isEqualTo(before.inventory());
        assertThat(repository.current().idempotency()).isEmpty();
    }

    @Test
    @DisplayName("a missing Idempotency-Key is rejected")
    void requiresIdempotencyKey() {
        assertThatThrownBy(() -> checkout.checkout(null, "a@b.com", List.of(new CheckoutLine("smartwatch", 1))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> checkout.checkout("  ", "a@b.com", List.of(new CheckoutLine("smartwatch", 1))))
                .isInstanceOf(ValidationException.class);
        assertNothingChanged();
    }

    @Test
    @DisplayName("an unknown product is rejected with a clear message")
    void rejectsUnknownProduct() {
        assertThatThrownBy(() -> checkout.checkout("k1", "a@b.com",
                List.of(new CheckoutLine("no-such-product", 1))))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getDetails())
                        .anySatisfy(detail -> assertThat(detail).contains("Unknown product: no-such-product")));
        assertNothingChanged();
    }

    @Test
    @DisplayName("zero, negative and huge quantities are rejected")
    void rejectsInvalidQuantities() {
        assertThatThrownBy(() -> checkout.checkout("k1", "a@b.com", List.of(new CheckoutLine("smartwatch", 0))))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> checkout.checkout("k2", "a@b.com", List.of(new CheckoutLine("smartwatch", -3))))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> checkout.checkout("k3", "a@b.com",
                List.of(new CheckoutLine("smartwatch", CheckoutService.MAX_QUANTITY_PER_LINE + 1))))
                .isInstanceOf(ValidationException.class);
        assertNothingChanged();
    }

    @Test
    @DisplayName("an empty cart is rejected")
    void rejectsEmptyCart() {
        assertThatThrownBy(() -> checkout.checkout("k1", "a@b.com", List.of()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one item");
        assertThatThrownBy(() -> checkout.checkout("k2", "a@b.com", null))
                .isInstanceOf(ValidationException.class);
        assertNothingChanged();
    }

    @Test
    @DisplayName("a missing or malformed user is rejected")
    void rejectsInvalidUser() {
        assertThatThrownBy(() -> checkout.checkout("k1", null, List.of(new CheckoutLine("smartwatch", 1))))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> checkout.checkout("k2", "", List.of(new CheckoutLine("smartwatch", 1))))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> checkout.checkout("k3", "not-an-email", List.of(new CheckoutLine("smartwatch", 1))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("valid e-mail");
        assertNothingChanged();
    }

    @Test
    @DisplayName("a null or empty product id is rejected")
    void rejectsMissingProductId() {
        assertThatThrownBy(() -> checkout.checkout("k1", "a@b.com",
                Arrays.asList(new CheckoutLine(null, 1))))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> checkout.checkout("k2", "a@b.com",
                List.of(new CheckoutLine("   ", 1))))
                .isInstanceOf(ValidationException.class);
        assertNothingChanged();
    }

    @Test
    @DisplayName("one invalid line rejects the whole cart (no partial order)")
    void rejectsWholeCartWhenOneLineIsInvalid() {
        assertThatThrownBy(() -> checkout.checkout("k1", "a@b.com", List.of(
                new CheckoutLine("smartwatch", 2),
                new CheckoutLine("ghost-product", 1))))
                .isInstanceOf(ValidationException.class);
        assertNothingChanged();
    }
}
