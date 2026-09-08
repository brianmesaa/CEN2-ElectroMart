package com.electromart.service;

import com.electromart.domain.CheckoutLine;
import com.electromart.domain.Order;
import com.electromart.domain.OrderLine;
import com.electromart.domain.Product;
import com.electromart.persistence.StateRepository;
import com.electromart.support.TestState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prices, line totals and the order total always come from the server catalog.
 */
class CheckoutPricingTest {

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
    @DisplayName("line totals and the order total are calculated from the catalog")
    void calculatesTotalsFromCatalog() {
        Order order = checkout.checkout("key-1", "a@b.com", List.of(
                new CheckoutLine("smartwatch", 2),
                new CheckoutLine("soundbar", 1))).order();

        long smartwatch = catalog.require("smartwatch").priceCents();
        long soundbar = catalog.require("soundbar").priceCents();

        assertThat(order.items()).extracting(OrderLine::productId).containsExactly("smartwatch", "soundbar");
        assertThat(order.items().get(0).unitPriceCents()).isEqualTo(smartwatch);
        assertThat(order.items().get(0).lineTotalCents()).isEqualTo(smartwatch * 2);
        assertThat(order.items().get(1).lineTotalCents()).isEqualTo(soundbar);
        assertThat(order.totalCents()).isEqualTo(smartwatch * 2 + soundbar);
    }

    @Test
    @DisplayName("repeated product ids are combined into a single line")
    void mergesRepeatedProducts() {
        Order order = checkout.checkout("key-1", "a@b.com", List.of(
                new CheckoutLine("4k-tv", 1),
                new CheckoutLine("4k-tv", 2),
                new CheckoutLine("4k-tv", 3))).order();

        long price = catalog.require("4k-tv").priceCents();
        assertThat(order.items()).hasSize(1);
        assertThat(order.items().get(0).quantity()).isEqualTo(6);
        assertThat(order.items().get(0).lineTotalCents()).isEqualTo(price * 6);
        assertThat(order.totalCents()).isEqualTo(price * 6);
        assertThat(repository.current().stockOf("4k-tv"))
                .isEqualTo(catalog.require("4k-tv").stock() - 6);
    }

    @Test
    @DisplayName("the order carries id, user, ISO-8601 date, names, quantities and prices")
    void ordersContainEveryRequiredField() {
        Order order = checkout.checkout("key-1", "buyer@example.com",
                List.of(new CheckoutLine("lg-oled-c4", 2))).order();

        assertThat(order.id()).isNotBlank();
        assertThat(order.user()).isEqualTo("buyer@example.com");
        assertThat(OffsetDateTime.parse(order.createdAt())).isNotNull(); // ISO-8601
        OrderLine line = order.items().get(0);
        Product product = catalog.require("lg-oled-c4");
        assertThat(line.productId()).isEqualTo(product.id());
        assertThat(line.name()).isEqualTo(product.name());
        assertThat(line.quantity()).isEqualTo(2);
        assertThat(line.unitPriceCents()).isEqualTo(product.priceCents());
        assertThat(line.lineTotalCents()).isEqualTo(product.priceCents() * 2);
        assertThat(order.totalCents()).isEqualTo(product.priceCents() * 2);
    }

    @Test
    @DisplayName("every order gets a unique id and inventory is reduced per order")
    void createsUniqueOrders() {
        Order first = checkout.checkout("key-1", "a@b.com", List.of(new CheckoutLine("smartwatch", 1))).order();
        Order second = checkout.checkout("key-2", "a@b.com", List.of(new CheckoutLine("smartwatch", 1))).order();

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(repository.current().orders()).hasSize(2);
        assertThat(repository.current().stockOf("smartwatch"))
                .isEqualTo(catalog.require("smartwatch").stock() - 2);
    }
}
