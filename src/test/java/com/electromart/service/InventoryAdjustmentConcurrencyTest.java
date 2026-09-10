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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent inventory adjustments and simultaneous checkouts must never lose updates.
 */
class InventoryAdjustmentConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("concurrent inventory adjustments serialize properly and never lose updates")
    void concurrentAdjustmentsNeverLoseUpdates() throws Exception {
        int initialStock = 10;
        int threads = 20;
        int qtyPerThread = 3;

        StateRepository repository = TestState.repository(
                tempDir.resolve("concurrent-adj-state.json"), Map.of("smartwatch", initialStock));
        InventoryAdjustmentService service = TestState.adjustmentService(repository);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.adjustStock("adj-key-" + index, "smartwatch", (long) qtyPerThread);
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(failures.get()).isZero();
        int expectedStock = initialStock + (threads * qtyPerThread);
        assertThat(repository.current().stockOf("smartwatch")).isEqualTo(expectedStock);

        // Reload to verify persisted state agrees with memory
        repository.reload();
        assertThat(repository.current().stockOf("smartwatch")).isEqualTo(expectedStock);
        assertThat(repository.current().adjustments()).hasSize(threads);
    }

    @Test
    @DisplayName("concurrent adjustments and checkouts share the same state and locking behavior without losing updates")
    void concurrentAdjustmentsAndCheckouts() throws Exception {
        int initialStock = 20;
        int adjustmentThreads = 15;
        int checkoutThreads = 10;
        int addPerThread = 4;
        int buyPerThread = 2;

        StateRepository repository = TestState.repository(
                tempDir.resolve("adj-checkout-state.json"), Map.of("msi-gaming-laptop", initialStock));
        InventoryAdjustmentService adjustmentService = TestState.adjustmentService(repository);
        CheckoutService checkoutService = TestState.checkoutService(repository);

        int totalThreads = adjustmentThreads + checkoutThreads;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(totalThreads);
        ExecutorService pool = Executors.newFixedThreadPool(totalThreads);
        AtomicInteger failures = new AtomicInteger();

        // Submit adjustment tasks
        for (int i = 0; i < adjustmentThreads; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    adjustmentService.adjustStock("adj-" + index, "msi-gaming-laptop", (long) addPerThread);
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        // Submit checkout tasks
        for (int i = 0; i < checkoutThreads; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    checkoutService.checkout("chk-" + index, "buyer" + index + "@example.com",
                            List.of(new CheckoutLine("msi-gaming-laptop", buyPerThread)));
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(failures.get()).isZero();
        int expectedStock = initialStock + (adjustmentThreads * addPerThread) - (checkoutThreads * buyPerThread);
        assertThat(repository.current().stockOf("msi-gaming-laptop")).isEqualTo(expectedStock);

        // Reload to verify disk matches
        repository.reload();
        assertThat(repository.current().stockOf("msi-gaming-laptop")).isEqualTo(expectedStock);
        assertThat(repository.current().adjustments()).hasSize(adjustmentThreads);
        assertThat(repository.current().orders()).hasSize(checkoutThreads);
    }
}
