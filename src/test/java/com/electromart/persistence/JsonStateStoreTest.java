package com.electromart.persistence;

import com.electromart.domain.CheckoutLine;
import com.electromart.domain.Order;
import com.electromart.domain.OrderLine;
import com.electromart.service.CatalogService;
import com.electromart.service.CheckoutService;
import com.electromart.support.TestState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The single JSON state file: initialization, validation and atomic writes.
 */
class JsonStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a missing state file is initialized from the catalog")
    void initializesFromCatalog() {
        Path file = tempDir.resolve("nested").resolve("state.json");
        StateRepository repository = TestState.repository(file);

        assertThat(Files.exists(file)).isTrue();
        CatalogService catalog = TestState.catalog();
        assertThat(repository.current().inventory()).hasSameSizeAs(catalog.products());
        catalog.products().forEach(product ->
                assertThat(repository.current().stockOf(product.id())).isEqualTo(product.stock()));
        assertThat(repository.current().orders()).isEmpty();
    }

    @Test
    @DisplayName("an invalid state file makes the server fail with a useful error instead of replacing it")
    void failsOnInvalidStateFile() throws Exception {
        Path file = tempDir.resolve("state.json");
        String broken = "{ this is not json";
        Files.writeString(file, broken, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> TestState.repository(file))
                .isInstanceOf(StateFileException.class)
                .hasMessageContaining(file.toAbsolutePath().toString());

        // the broken file is kept untouched
        assertThat(Files.readString(file)).isEqualTo(broken);
    }

    @Test
    @DisplayName("structurally valid but semantically broken state files are rejected too")
    void failsOnSemanticallyInvalidState() throws Exception {
        Path file = tempDir.resolve("state.json");
        Files.writeString(file, "{\"version\":1,\"inventory\":{\"smartwatch\":-4},\"orders\":[],\"idempotency\":{}}");
        assertThatThrownBy(() -> new JsonStateStore(file).read())
                .isInstanceOf(StateFileException.class)
                .hasMessageContaining("negative stock");

        Files.writeString(file, "{\"version\":0,\"inventory\":{},\"orders\":[],\"idempotency\":{}}");
        assertThatThrownBy(() -> new JsonStateStore(file).read())
                .isInstanceOf(StateFileException.class)
                .hasMessageContaining("invalid version");

        Files.writeString(file, "{\"version\":1,\"inventory\":{},\"orders\":[{\"id\":\"\",\"user\":\"a@b.com\","
                + "\"createdAt\":\"2024-01-01T00:00:00Z\",\"items\":[],\"totalCents\":0}],\"idempotency\":{}}");
        assertThatThrownBy(() -> new JsonStateStore(file).read())
                .isInstanceOf(StateFileException.class)
                .hasMessageContaining("without id");
    }

    @Test
    @DisplayName("writes are atomic and leave no temporary files behind")
    void writesAtomically() throws Exception {
        Path file = tempDir.resolve("state.json");
        JsonStateStore store = new JsonStateStore(file);

        store.write(new PersistentState(1, Map.of("smartwatch", 5), List.of(), Map.of()));
        Order order = new Order("ord_1", "a@b.com", "2024-01-01T00:00:00Z",
                List.of(new OrderLine("smartwatch", "Smartwatch", 1, 15000, 15000)), 15000);
        store.write(new PersistentState(1, Map.of("smartwatch", 4), List.of(order), Map.of()));

        PersistentState reloaded = store.read().orElseThrow();
        assertThat(reloaded.stockOf("smartwatch")).isEqualTo(4);
        assertThat(reloaded.orders()).containsExactly(order);

        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .containsExactly("state.json");
        }
    }

    @Test
    @DisplayName("orders, inventory and idempotency records survive a restart")
    void survivesRestart() {
        Path file = tempDir.resolve("state.json");

        StateRepository first = TestState.repository(file, Map.of("smartwatch", 6));
        CheckoutService checkout = TestState.checkoutService(first);
        Order order = checkout.checkout("key-1", "a@b.com", List.of(new CheckoutLine("smartwatch", 2))).order();

        // "restart": a brand new repository reading the same file
        StateRepository restarted = new StateRepository(new JsonStateStore(file), TestState.catalog());

        assertThat(restarted.current().stockOf("smartwatch")).isEqualTo(4);
        assertThat(restarted.current().orders()).containsExactly(order);
        assertThat(restarted.current().idempotency()).containsKey("key-1");

        // and the idempotent replay still works after the restart, without taking stock again
        CheckoutService afterRestart = TestState.checkoutService(restarted);
        assertThat(afterRestart.checkout("key-1", "a@b.com", List.of(new CheckoutLine("smartwatch", 2))).replay())
                .isTrue();
        assertThat(restarted.current().stockOf("smartwatch")).isEqualTo(4);
        assertThat(restarted.current().orders()).hasSize(1);
    }

    @Test
    @DisplayName("products added to the catalog later appear in an existing state file")
    void addsNewCatalogProductsToAnExistingFile() throws Exception {
        Path file = tempDir.resolve("state.json");
        Files.writeString(file, "{\"version\":1,\"inventory\":{\"smartwatch\":2},\"orders\":[],\"idempotency\":{}}");

        StateRepository repository = TestState.repository(file);

        assertThat(repository.current().stockOf("smartwatch")).isEqualTo(2);
        assertThat(repository.current().stockOf("soundbar"))
                .isEqualTo(TestState.catalog().require("soundbar").stock());
    }
}
