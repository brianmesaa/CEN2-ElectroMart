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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simultaneous checkouts must never oversell a product and a key used concurrently
 * must still create only one order.
 */
class CheckoutConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("concurrent checkouts cannot oversell: exactly the available units are sold")
    void concurrentCheckoutsCannotOversell() throws Exception {
        int stock = 5;
        int threads = 24;
        StateRepository repository = TestState.repository(tempDir.resolve("state.json"), Map.of("lg-oled-c4", stock));
        CheckoutService checkout = TestState.checkoutService(repository);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    checkout.checkout("key-" + index, "buyer" + index + "@example.com",
                            List.of(new CheckoutLine("lg-oled-c4", 1)));
                    succeeded.incrementAndGet();
                } catch (InsufficientStockException e) {
                    soldOut.incrementAndGet();
                } catch (Exception e) {
                    unexpected.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(unexpected.get()).isZero();
        assertThat(succeeded.get()).isEqualTo(stock);
        assertThat(soldOut.get()).isEqualTo(threads - stock);
        assertThat(repository.current().stockOf("lg-oled-c4")).isZero();
        assertThat(repository.current().orders()).hasSize(stock);

        // the persisted file agrees with memory
        repository.reload();
        assertThat(repository.current().stockOf("lg-oled-c4")).isZero();
        assertThat(repository.current().orders()).hasSize(stock);
    }

    @Test
    @DisplayName("the same idempotency key used concurrently creates exactly one order")
    void concurrentRetriesWithTheSameKeyCreateOneOrder() throws Exception {
        int threads = 16;
        StateRepository repository = TestState.repository(tempDir.resolve("state.json"), Map.of("smartwatch", 10));
        CheckoutService checkout = TestState.checkoutService(repository);

        Set<String> orderIds = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    CheckoutResult result = checkout.checkout("same-key", "a@b.com",
                            List.of(new CheckoutLine("smartwatch", 2)));
                    orderIds.add(result.order().id());
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
        assertThat(orderIds).hasSize(1);
        assertThat(repository.current().orders()).hasSize(1);
        assertThat(repository.current().stockOf("smartwatch")).isEqualTo(8);
    }
}
