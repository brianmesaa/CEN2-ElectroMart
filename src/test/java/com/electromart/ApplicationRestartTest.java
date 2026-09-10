package com.electromart;

import com.electromart.domain.CheckoutLine;
import com.electromart.domain.Order;
import com.electromart.persistence.StateFileException;
import com.electromart.service.CheckoutService;
import com.electromart.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Starts the whole application twice on the same state file to prove that inventory,
 * orders and idempotency records survive a restart, and that a broken state file
 * stops the server with a useful error.
 */
class ApplicationRestartTest {

    @TempDir
    Path tempDir;

    private ConfigurableApplicationContext start(Path dataFile) {
        // command line arguments take precedence over application.properties
        return new SpringApplicationBuilder(ElectroMartApplication.class)
                .run("--server.port=0", "--electromart.data-file=" + dataFile);
    }

    @Test
    @DisplayName("orders and inventory survive a full restart")
    void stateSurvivesRestart() {
        Path dataFile = tempDir.resolve("restart-state.json");
        Order order;
        int stockAfterCheckout;

        try (ConfigurableApplicationContext first = start(dataFile)) {
            CheckoutService checkout = first.getBean(CheckoutService.class);
            order = checkout.checkout("restart-key", "restart@example.com",
                    List.of(new CheckoutLine("microsoft-surface-laptop", 2))).order();
            stockAfterCheckout = checkout.currentStock().get("microsoft-surface-laptop");
        }

        assertThat(Files.exists(dataFile)).isTrue();

        try (ConfigurableApplicationContext second = start(dataFile)) {
            OrderService orders = second.getBean(OrderService.class);
            CheckoutService checkout = second.getBean(CheckoutService.class);

            assertThat(orders.ordersOf("restart@example.com")).containsExactly(order);
            assertThat(checkout.currentStock().get("microsoft-surface-laptop")).isEqualTo(stockAfterCheckout);

            // the idempotency record survived too: the retry returns the same order
            assertThat(checkout.checkout("restart-key", "restart@example.com",
                    List.of(new CheckoutLine("microsoft-surface-laptop", 2))).replay()).isTrue();
            assertThat(checkout.currentStock().get("microsoft-surface-laptop")).isEqualTo(stockAfterCheckout);
            assertThat(orders.ordersOf("restart@example.com")).hasSize(1);
        }
    }

    private static List<Throwable> causeChainOf(Throwable throwable) {
        List<Throwable> chain = new java.util.ArrayList<>();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    @Test
    @DisplayName("a corrupted state file stops the server with a clear error")
    void refusesToStartWithABrokenStateFile() throws Exception {
        Path dataFile = tempDir.resolve("broken-state.json");
        Files.writeString(dataFile, "{\"version\": 1, \"inventory\": {\"smartwatch\": ");

        Throwable thrown = catchThrowable(() -> start(dataFile).close());

        assertThat(thrown).isNotNull();
        assertThat(causeChainOf(thrown)).hasAtLeastOneElementOfType(StateFileException.class);
        assertThat(thrown).hasStackTraceContaining("is not valid ElectroMart state JSON")
                .hasStackTraceContaining(dataFile.toAbsolutePath().toString());

        assertThat(Files.readString(dataFile)).startsWith("{\"version\": 1");
    }
}
