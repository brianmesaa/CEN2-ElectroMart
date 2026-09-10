package com.electromart.support;

import com.electromart.domain.Order;
import com.electromart.persistence.JsonStateStore;
import com.electromart.persistence.PersistentState;
import com.electromart.persistence.StateRepository;
import com.electromart.service.CatalogService;
import com.electromart.service.CheckoutService;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small helpers to build a service stack on top of a temporary state file.
 */
public final class TestState {

    private TestState() {
    }

    public static CatalogService catalog() {
        return new CatalogService();
    }

    /** Repository backed by {@code file}; when {@code stock} is given it is written first. */
    public static StateRepository repository(Path file, Map<String, Integer> stock) {
        JsonStateStore store = new JsonStateStore(file);
        if (stock != null && !stock.isEmpty()) {
            Map<String, Integer> inventory = new LinkedHashMap<>(catalog().initialStock());
            inventory.putAll(stock);
            store.write(new PersistentState(PersistentState.CURRENT_VERSION, inventory, List.of(), Map.of()));
        }
        return new StateRepository(store, catalog());
    }

    public static StateRepository repository(Path file) {
        return repository(file, Map.of());
    }

    public static CheckoutService checkoutService(StateRepository repository) {
        return new CheckoutService(catalog(), repository);
    }

    public static Order onlyOrder(StateRepository repository) {
        List<Order> orders = repository.current().orders();
        if (orders.size() != 1) {
            throw new AssertionError("Expected exactly one order but found " + orders.size());
        }
        return orders.get(0);
    }
}
