package com.electromart.persistence;

import com.electromart.domain.InventoryAdjustmentRecord;
import com.electromart.domain.Order;
import com.electromart.service.CatalogService;
import com.electromart.service.InventoryAdjustmentResult;
import com.electromart.service.InventoryAdjustmentService;
import com.electromart.support.TestState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryAdjustmentPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("version 1 state files migrate cleanly to version 2 without losing orders, inventory, or idempotency")
    void migratesFromVersion1() throws Exception {
        Path file = tempDir.resolve("v1-state.json");
        String v1Json = """
                {
                  "version": 1,
                  "inventory": {
                    "smartwatch": 7
                  },
                  "orders": [
                    {
                      "id": "ord_migrated",
                      "user": "v1user@example.com",
                      "createdAt": "2024-01-01T00:00:00Z",
                      "items": [
                        {
                          "productId": "smartwatch",
                          "name": "Smartwatch",
                          "quantity": 2,
                          "unitPriceCents": 15000,
                          "lineTotalCents": 30000
                        }
                      ],
                      "totalCents": 30000
                    }
                  ],
                  "idempotency": {
                    "chk-v1-key": {
                      "key": "chk-v1-key",
                      "requestHash": "somehash",
                      "orderId": "ord_migrated"
                    }
                  }
                }
                """;
        Files.writeString(file, v1Json, StandardCharsets.UTF_8);

        // Load into repository
        StateRepository repository = new StateRepository(new JsonStateStore(file), TestState.catalog());

        // State is migrated to current version
        assertThat(repository.current().version()).isEqualTo(PersistentState.CURRENT_VERSION);
        assertThat(repository.current().stockOf("smartwatch")).isEqualTo(7);
        assertThat(repository.current().orders()).hasSize(1);
        Order order = repository.current().orders().get(0);
        assertThat(order.id()).isEqualTo("ord_migrated");
        assertThat(order.user()).isEqualTo("v1user@example.com");
        assertThat(repository.current().idempotency()).containsKey("chk-v1-key");
        assertThat(repository.current().adjustments()).isEmpty();

        // Disk was updated to version 2
        JsonStateStore store = new JsonStateStore(file);
        PersistentState onDisk = store.read().orElseThrow();
        assertThat(onDisk.version()).isEqualTo(PersistentState.CURRENT_VERSION);
        assertThat(onDisk.orders()).hasSize(1);
        assertThat(onDisk.idempotency()).containsKey("chk-v1-key");

        // Subsequent adjustment works on the migrated repository
        InventoryAdjustmentService service = TestState.adjustmentService(repository);
        InventoryAdjustmentResult result = service.adjustStock("migrated-adj-key", "smartwatch", 5L);
        assertThat(result.resultingStock()).isEqualTo(12);
        assertThat(repository.current().stockOf("smartwatch")).isEqualTo(12);
    }

    @Test
    @DisplayName("inventory adjustments survive an application restart")
    void survivesRestart() {
        Path file = tempDir.resolve("restart-adj-state.json");
        CatalogService catalog = TestState.catalog();

        StateRepository firstRepo = new StateRepository(new JsonStateStore(file), catalog);
        InventoryAdjustmentService firstService = new InventoryAdjustmentService(catalog, firstRepo);

        int initialStock = firstRepo.current().stockOf("soundbar");
        firstService.adjustStock("restart-key", "soundbar", 8L);
        int stockAfterAdjustment = firstRepo.current().stockOf("soundbar");
        assertThat(stockAfterAdjustment).isEqualTo(initialStock + 8);

        // Simulate restart with new repository reading the same file
        StateRepository secondRepo = new StateRepository(new JsonStateStore(file), catalog);
        assertThat(secondRepo.current().stockOf("soundbar")).isEqualTo(stockAfterAdjustment);
        assertThat(secondRepo.current().adjustments()).containsKey("restart-key");
        InventoryAdjustmentRecord record = secondRepo.current().adjustments().get("restart-key");
        assertThat(record.quantityAdded()).isEqualTo(8);
        assertThat(record.resultingStock()).isEqualTo(stockAfterAdjustment);

        // Retrying with the same key after restart replays original result without re-adding
        InventoryAdjustmentService secondService = new InventoryAdjustmentService(catalog, secondRepo);
        InventoryAdjustmentResult retry = secondService.adjustStock("restart-key", "soundbar", 8L);
        assertThat(retry.replay()).isTrue();
        assertThat(retry.resultingStock()).isEqualTo(stockAfterAdjustment);
        assertThat(secondRepo.current().stockOf("soundbar")).isEqualTo(stockAfterAdjustment);
    }

    @Test
    @DisplayName("persistence failure leaves in-memory state unchanged and does not report success")
    void persistenceFailureLeavesStateUnchanged() {
        Path file = tempDir.resolve("fail-state.json");
        CatalogService catalog = TestState.catalog();

        // Custom failing store
        class FailingJsonStateStore extends JsonStateStore {
            private boolean fail = false;

            public FailingJsonStateStore(Path file) {
                super(file);
            }

            @Override
            public void write(PersistentState state) {
                if (fail) {
                    throw new StateFileException("Disk simulated failure");
                }
                super.write(state);
            }
        }

        FailingJsonStateStore failingStore = new FailingJsonStateStore(file);
        StateRepository repository = new StateRepository(failingStore, catalog);
        InventoryAdjustmentService service = new InventoryAdjustmentService(catalog, repository);

        int stockBefore = repository.current().stockOf("smartwatch");

        // Enable write failure
        failingStore.fail = true;

        assertThatThrownBy(() -> service.adjustStock("fail-key", "smartwatch", 10L))
                .isInstanceOf(StateFileException.class)
                .hasMessageContaining("Disk simulated failure");

        // In-memory inventory must remain unchanged
        assertThat(repository.current().stockOf("smartwatch")).isEqualTo(stockBefore);
        assertThat(repository.current().adjustments()).doesNotContainKey("fail-key");
    }
}
