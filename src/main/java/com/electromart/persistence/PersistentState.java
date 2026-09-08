package com.electromart.persistence;

import com.electromart.domain.IdempotencyRecord;
import com.electromart.domain.Order;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of everything the server persists in the single JSON state file:
 * the remaining inventory, the completed orders and the idempotency records.
 *
 * <p>Instances are never mutated; a checkout builds a new snapshot which is written to disk
 * before it becomes visible, which keeps the on-disk file and memory consistent.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PersistentState(
        int version,
        Map<String, Integer> inventory,
        List<Order> orders,
        Map<String, IdempotencyRecord> idempotency) {

    public static final int CURRENT_VERSION = 1;

    public PersistentState {
        inventory = inventory == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(inventory));
        orders = orders == null ? List.of() : List.copyOf(orders);
        idempotency = idempotency == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(idempotency));
    }

    public static PersistentState empty() {
        return new PersistentState(CURRENT_VERSION, Map.of(), List.of(), Map.of());
    }

    public int stockOf(String productId) {
        return inventory.getOrDefault(productId, 0);
    }
}
