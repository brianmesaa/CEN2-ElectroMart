package com.electromart.persistence;

import com.electromart.domain.IdempotencyRecord;
import com.electromart.domain.InventoryAdjustmentRecord;
import com.electromart.domain.Order;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of everything the server persists in the single JSON state file:
 * the remaining inventory, the completed orders, the checkout idempotency records,
 * and the inventory adjustment idempotency records.
 *
 * <p>Instances are never mutated; a checkout or inventory adjustment builds a new snapshot which is written to disk
 * before it becomes visible, which keeps the on-disk file and memory consistent.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PersistentState(
        int version,
        Map<String, Integer> inventory,
        List<Order> orders,
        Map<String, IdempotencyRecord> idempotency,
        @JsonAlias({"inventoryAdjustments", "adjustments"})
        Map<String, InventoryAdjustmentRecord> adjustments) {

    public static final int CURRENT_VERSION = 2;

    public PersistentState {
        inventory = inventory == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(inventory));
        orders = orders == null ? List.of() : List.copyOf(orders);
        idempotency = idempotency == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(idempotency));
        adjustments = adjustments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(adjustments));
    }

    public PersistentState(int version,
                           Map<String, Integer> inventory,
                           List<Order> orders,
                           Map<String, IdempotencyRecord> idempotency) {
        this(version, inventory, orders, idempotency, Map.of());
    }

    public static PersistentState empty() {
        return new PersistentState(CURRENT_VERSION, Map.of(), List.of(), Map.of(), Map.of());
    }

    @JsonIgnore
    public Map<String, InventoryAdjustmentRecord> inventoryAdjustments() {
        return adjustments;
    }

    public int stockOf(String productId) {
        return inventory.getOrDefault(productId, 0);
    }
}
