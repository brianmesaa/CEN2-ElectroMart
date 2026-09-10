package com.electromart.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Owns the in-memory copy of the persisted state and turns every change into a single
 * serialized "transaction": the whole mutation is computed under a lock, written to disk
 * atomically and only then published.
 *
 * <p>Consequences:</p>
 * <ul>
 *   <li>Concurrent checkouts cannot oversell a product (the lock serializes them).</li>
 *   <li>A failed or rejected operation leaves neither inventory nor orders changed.</li>
 *   <li>The file on disk and the in-memory state never diverge.</li>
 * </ul>
 */
public class StateRepository {

    private static final Logger log = LoggerFactory.getLogger(StateRepository.class);

    private final JsonStateStore store;
    private final InventoryDefaults defaults;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile PersistentState state;

    public StateRepository(JsonStateStore store, InventoryDefaults defaults) {
        this.store = store;
        this.defaults = defaults;
        this.state = loadOrInitialize();
    }

    private PersistentState loadOrInitialize() {
        Optional<PersistentState> loaded = store.read();
        if (loaded.isEmpty()) {
            PersistentState initial = new PersistentState(
                    PersistentState.CURRENT_VERSION, defaults.initialStock(), java.util.List.of(), Map.of());
            store.write(initial);
            log.info("Initialized new ElectroMart state file from the catalog at {}", store.file());
            return initial;
        }
        PersistentState existing = loaded.get();
        PersistentState reconciled = addMissingCatalogProducts(existing);
        if (reconciled != existing) {
            store.write(reconciled);
            log.info("Added new catalog products to the existing state file at {}", store.file());
            return reconciled;
        }
        log.info("Loaded ElectroMart state from {} ({} orders)", store.file(), existing.orders().size());
        return existing;
    }

    /** Products added to the catalog after the state file was written start with their catalog stock. */
    private PersistentState addMissingCatalogProducts(PersistentState existing) {
        Map<String, Integer> merged = new LinkedHashMap<>(existing.inventory());
        boolean changed = false;
        for (Map.Entry<String, Integer> entry : defaults.initialStock().entrySet()) {
            if (!merged.containsKey(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        if (!changed) {
            return existing;
        }
        return new PersistentState(existing.version(), merged, existing.orders(), existing.idempotency());
    }

    /** The current (immutable) state snapshot. */
    public PersistentState current() {
        return state;
    }

    public Path fileLocation() {
        return store.file();
    }

    /**
     * Runs {@code work} as a single transaction. The callback receives the current snapshot and
     * returns the result plus, optionally, the new state to persist.
     *
     * @throws RuntimeException any exception thrown by the callback aborts the transaction and
     *                          leaves the persisted state untouched
     */
    public <T> T transact(Function<PersistentState, TransactionResult<T>> work) {
        lock.lock();
        try {
            PersistentState before = state;
            TransactionResult<T> outcome = work.apply(before);
            PersistentState next = outcome.newState();
            if (next != null && next != before) {
                store.write(next);
                state = next;
            }
            return outcome.result();
        } finally {
            lock.unlock();
        }
    }

    /** Re-reads the state file, used by tests that simulate a restart. */
    public void reload() {
        lock.lock();
        try {
            this.state = loadOrInitialize();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The outcome of a transaction: the state to persist (or {@code null} when nothing changed)
     * and the value returned to the caller.
     */
    public record TransactionResult<T>(PersistentState newState, T result) {

        public static <T> TransactionResult<T> unchanged(T result) {
            return new TransactionResult<>(null, result);
        }

        public static <T> TransactionResult<T> changed(PersistentState newState, T result) {
            return new TransactionResult<>(newState, result);
        }
    }
}
