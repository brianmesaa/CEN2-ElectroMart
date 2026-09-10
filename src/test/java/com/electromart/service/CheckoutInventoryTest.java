package com.electromart.service;

import com.electromart.domain.CheckoutLine;
import com.electromart.persistence.StateRepository;
import com.electromart.support.TestState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inventory conflicts: too little stock rejects the whole checkout and changes nothing.
 */
class CheckoutInventoryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("insufficient stock is reported with the requested and available amount")
    void rejectsWhenStockIsTooLow() {
        StateRepository repository = TestState.repository(tempDir.resolve("state.json"), Map.of("smartwatch", 3));
        CheckoutService checkout = TestState.checkoutService(repository);

        assertThatThrownBy(() -> checkout.checkout("key-1", "a@b.com", List.of(new CheckoutLine("smartwatch", 4))))
                .isInstanceOf(InsufficientStockException.class)
                .satisfies(e -> {
                    InsufficientStockException ex = (InsufficientStockException) e;
                    assertThat(ex.getShortages()).hasSize(1);
                    assertThat(ex.getShortages().get(0).productId()).isEqualTo("smartwatch");
                    assertThat(ex.getShortages().get(0).requested()).isEqualTo(4);
                    assertThat(ex.getShortages().get(0).available()).isEqualTo(3);
                });

        assertThat(repository.current().stockOf("smartwatch")).isEqualTo(3);
        assertThat(repository.current().orders()).isEmpty();
        assertThat(repository.current().idempotency()).isEmpty();
    }

    @Test
    @DisplayName("merged quantities are checked against the stock as a whole")
    void checksMergedQuantities() {
        StateRepository repository = TestState.repository(tempDir.resolve("state.json"), Map.of("soundbar", 2));
        CheckoutService checkout = TestState.checkoutService(repository);

        assertThatThrownBy(() -> checkout.checkout("key-1", "a@b.com", List.of(
                new CheckoutLine("soundbar", 1),
                new CheckoutLine("soundbar", 2))))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(repository.current().stockOf("soundbar")).isEqualTo(2);
        assertThat(repository.current().orders()).isEmpty();
    }

    @Test
    @DisplayName("a failing line leaves the other products of the cart untouched (no partial order)")
    void doesNotCreateAPartialOrder() {
        StateRepository repository = TestState.repository(tempDir.resolve("state.json"),
                Map.of("smartwatch", 5, "lg-oled-c4", 1));
        CheckoutService checkout = TestState.checkoutService(repository);

        assertThatThrownBy(() -> checkout.checkout("key-1", "a@b.com", List.of(
                new CheckoutLine("smartwatch", 2),
                new CheckoutLine("lg-oled-c4", 3))))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(repository.current().stockOf("smartwatch")).isEqualTo(5);
        assertThat(repository.current().stockOf("lg-oled-c4")).isEqualTo(1);
        assertThat(repository.current().orders()).isEmpty();
    }

    @Test
    @DisplayName("buying exactly the remaining stock works and leaves zero")
    void allowsBuyingTheLastUnits() {
        StateRepository repository = TestState.repository(tempDir.resolve("state.json"), Map.of("oled-tv", 2));
        CheckoutService checkout = TestState.checkoutService(repository);

        checkout.checkout("key-1", "a@b.com", List.of(new CheckoutLine("oled-tv", 2)));

        assertThat(repository.current().stockOf("oled-tv")).isZero();
        assertThatThrownBy(() -> checkout.checkout("key-2", "a@b.com", List.of(new CheckoutLine("oled-tv", 1))))
                .isInstanceOf(InsufficientStockException.class);
    }
}
