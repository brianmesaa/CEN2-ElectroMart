package com.electromart.service;

import com.electromart.domain.CheckoutLine;
import com.electromart.domain.Order;
import com.electromart.persistence.StateRepository;
import com.electromart.support.TestState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Idempotency: retrying the same checkout is safe, reusing a key for something else is not.
 */
class CheckoutIdempotencyTest {

    @TempDir
    Path tempDir;

    private CatalogService catalog;
    private StateRepository repository;
    private CheckoutService checkout;

    @BeforeEach
    void setUp() {
        catalog = TestState.catalog();
        repository = TestState.repository(tempDir.resolve("state.json"));
        checkout = TestState.checkoutService(repository);
    }

    @Test
    @DisplayName("the same key and the same cart return the original order without taking stock again")
    void replaysTheOriginalOrder() {
        int initialStock = catalog.require("smartwatch").stock();

        CheckoutResult first = checkout.checkout("key-1", "a@b.com", List.of(new CheckoutLine("smartwatch", 2)));
        CheckoutResult second = checkout.checkout("key-1", "a@b.com", List.of(new CheckoutLine("smartwatch", 2)));

        assertThat(first.replay()).isFalse();
        assertThat(second.replay()).isTrue();
        assertThat(second.order()).isEqualTo(first.order());
        assertThat(repository.current().orders()).hasSize(1);
        assertThat(repository.current().stockOf("smartwatch")).isEqualTo(initialStock - 2);
    }

    @Test
    @DisplayName("the replay ignores the order of the items and duplicate lines")
    void replayNormalizesTheRequest() {
        Order first = checkout.checkout("key-1", "a@b.com", List.of(
                new CheckoutLine("smartwatch", 1),
                new CheckoutLine("soundbar", 2))).order();

        CheckoutResult replay = checkout.checkout("key-1", "a@b.com", List.of(
                new CheckoutLine("soundbar", 1),
                new CheckoutLine("soundbar", 1),
                new CheckoutLine("smartwatch", 1)));

        assertThat(replay.replay()).isTrue();
        assertThat(replay.order().id()).isEqualTo(first.id());
        assertThat(repository.current().orders()).hasSize(1);
    }

    @Test
    @DisplayName("reusing the key for a different cart is a conflict")
    void rejectsKeyReuseForAnotherCart() {
        checkout.checkout("key-1", "a@b.com", List.of(new CheckoutLine("smartwatch", 1)));
        int stockAfterFirst = repository.current().stockOf("soundbar");

        assertThatThrownBy(() -> checkout.checkout("key-1", "a@b.com", List.of(new CheckoutLine("soundbar", 1))))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("key-1");

        assertThat(repository.current().orders()).hasSize(1);
        assertThat(repository.current().stockOf("soundbar")).isEqualTo(stockAfterFirst);
    }

    @Test
    @DisplayName("reusing the key for a different user is a conflict")
    void rejectsKeyReuseForAnotherUser() {
        checkout.checkout("key-1", "a@b.com", List.of(new CheckoutLine("smartwatch", 1)));

        assertThatThrownBy(() -> checkout.checkout("key-1", "other@b.com", List.of(new CheckoutLine("smartwatch", 1))))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(repository.current().orders()).hasSize(1);
    }

    @Test
    @DisplayName("the idempotency record is persisted in the state file")
    void storesTheIdempotencyRecord() {
        Order order = checkout.checkout("key-1", "a@b.com", List.of(new CheckoutLine("smartwatch", 1))).order();

        assertThat(repository.current().idempotency()).containsKey("key-1");
        assertThat(repository.current().idempotency().get("key-1").orderId()).isEqualTo(order.id());
    }
}
