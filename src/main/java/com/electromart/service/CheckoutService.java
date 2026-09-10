package com.electromart.service;

import com.electromart.domain.CheckoutLine;
import com.electromart.domain.IdempotencyRecord;
import com.electromart.domain.Order;
import com.electromart.domain.OrderLine;
import com.electromart.domain.Product;
import com.electromart.persistence.PersistentState;
import com.electromart.persistence.StateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * All checkout business rules live here:
 *
 * <ul>
 *   <li>the request is fully validated before anything is changed;</li>
 *   <li>repeated product ids are merged into a single quantity;</li>
 *   <li>prices, line totals and the order total are calculated from the server catalog;</li>
 *   <li>the order creation and the stock decrement happen in one transaction;</li>
 *   <li>an {@code Idempotency-Key} makes a retry safe and detects reuse for another cart.</li>
 * </ul>
 */
@Service
public class CheckoutService {

    /** Practical upper bound per line, keeps totals far away from any overflow. */
    public static final int MAX_QUANTITY_PER_LINE = 1_000_000;

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final CatalogService catalog;
    private final StateRepository repository;
    private final Clock clock;
    private final Supplier<String> orderIdGenerator;

    @Autowired
    public CheckoutService(CatalogService catalog, StateRepository repository) {
        this(catalog, repository, Clock.systemUTC(), () -> "ord_" + UUID.randomUUID());
    }

    /** Test friendly constructor with injectable clock and order id generator. */
    public CheckoutService(CatalogService catalog,
                           StateRepository repository,
                           Clock clock,
                           Supplier<String> orderIdGenerator) {
        this.catalog = catalog;
        this.repository = repository;
        this.clock = clock;
        this.orderIdGenerator = orderIdGenerator;
    }

    /**
     * Validates the request, calculates every price from the catalog and, if everything is fine,
     * creates exactly one order while decrementing the inventory.
     *
     * @throws ValidationException           invalid input (HTTP 400)
     * @throws InsufficientStockException    not enough stock (HTTP 409)
     * @throws IdempotencyConflictException  key reused for a different user or cart (HTTP 409)
     */
    public CheckoutResult checkout(String idempotencyKey, String user, List<CheckoutLine> requestedItems) {
        String key = requireIdempotencyKey(idempotencyKey);
        String buyer = requireUser(user);
        List<CheckoutLine> items = normalize(requestedItems);
        String requestHash = fingerprint(buyer, items);

        return repository.transact(state -> {
            IdempotencyRecord existing = state.idempotency().get(key);
            if (existing != null) {
                if (!existing.requestHash().equals(requestHash)) {
                    throw new IdempotencyConflictException(key,
                            "Idempotency-Key '" + key + "' was already used for a different checkout request.");
                }
                Order previous = findOrder(state, existing.orderId()).orElseThrow(() -> new IllegalStateException(
                        "Idempotency record '" + key + "' points to unknown order " + existing.orderId()));
                // Replay: same key, same request -> return the original order, take no stock.
                return StateRepository.TransactionResult.unchanged(new CheckoutResult(previous, true));
            }

            checkStock(state, items);

            Order order = buildOrder(buyer, items);
            Map<String, Integer> inventory = new LinkedHashMap<>(state.inventory());
            for (CheckoutLine line : items) {
                inventory.put(line.productId(), inventory.get(line.productId()) - line.quantity());
            }
            List<Order> orders = new ArrayList<>(state.orders());
            orders.add(order);
            Map<String, IdempotencyRecord> idempotency = new LinkedHashMap<>(state.idempotency());
            idempotency.put(key, new IdempotencyRecord(key, requestHash, order.id()));

            PersistentState next = new PersistentState(state.version(), inventory, orders, idempotency);
            return StateRepository.TransactionResult.changed(next, new CheckoutResult(order, false));
        });
    }

    private String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ValidationException("The Idempotency-Key header is required.");
        }
        String key = idempotencyKey.trim();
        if (key.length() > 200) {
            throw new ValidationException("The Idempotency-Key header must not be longer than 200 characters.");
        }
        return key;
    }

    private String requireUser(String user) {
        if (user == null || user.isBlank()) {
            throw new ValidationException("A user e-mail address is required.");
        }
        String buyer = user.trim();
        if (!EMAIL.matcher(buyer).matches()) {
            throw new ValidationException("'" + buyer + "' is not a valid e-mail address.");
        }
        return buyer;
    }

    /** Validates every line and merges repeated product ids into a single quantity. */
    private List<CheckoutLine> normalize(List<CheckoutLine> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new ValidationException("The cart must contain at least one item.");
        }
        List<String> problems = new ArrayList<>();
        Set<String> unknown = new LinkedHashSet<>();
        Map<String, Integer> quantities = new LinkedHashMap<>();

        for (int i = 0; i < requested.size(); i++) {
            CheckoutLine line = requested.get(i);
            if (line == null || line.productId() == null || line.productId().isBlank()) {
                problems.add("items[" + i + "]: productId is required.");
                continue;
            }
            String productId = line.productId().trim();
            int quantity = line.quantity();
            if (quantity <= 0) {
                problems.add("items[" + i + "]: quantity must be a positive integer (was " + quantity + ").");
                continue;
            }
            if (quantity > MAX_QUANTITY_PER_LINE) {
                problems.add("items[" + i + "]: quantity must not exceed " + MAX_QUANTITY_PER_LINE + ".");
                continue;
            }
            if (!catalog.contains(productId)) {
                unknown.add(productId);
                continue;
            }
            long merged = (long) quantities.getOrDefault(productId, 0) + quantity;
            if (merged > MAX_QUANTITY_PER_LINE) {
                problems.add("items[" + i + "]: total quantity for '" + productId + "' must not exceed "
                        + MAX_QUANTITY_PER_LINE + ".");
                continue;
            }
            quantities.put(productId, (int) merged);
        }

        unknown.forEach(id -> problems.add("Unknown product: " + id));

        if (!problems.isEmpty()) {
            throw new ValidationException("The checkout request is invalid.", problems);
        }
        if (quantities.isEmpty()) {
            throw new ValidationException("The cart must contain at least one item.");
        }

        List<CheckoutLine> normalized = new ArrayList<>(quantities.size());
        quantities.forEach((id, qty) -> normalized.add(new CheckoutLine(id, qty)));
        return List.copyOf(normalized);
    }

    /** Verifies availability for the complete cart before anything is written. */
    private void checkStock(PersistentState state, List<CheckoutLine> items) {
        List<InsufficientStockException.Shortage> shortages = new ArrayList<>();
        for (CheckoutLine line : items) {
            Product product = catalog.require(line.productId());
            int available = state.stockOf(line.productId());
            if (available < line.quantity()) {
                shortages.add(new InsufficientStockException.Shortage(
                        product.id(), product.name(), line.quantity(), available));
            }
        }
        if (!shortages.isEmpty()) {
            String message = shortages.size() == 1
                    ? "Insufficient stock for " + shortages.get(0).name() + ": requested "
                        + shortages.get(0).requested() + ", available " + shortages.get(0).available() + "."
                    : "Insufficient stock for " + shortages.size() + " products.";
            throw new InsufficientStockException(message, shortages);
        }
    }

    /** Builds the order with server side prices only. */
    private Order buildOrder(String user, List<CheckoutLine> items) {
        List<OrderLine> lines = new ArrayList<>(items.size());
        long total = 0;
        for (CheckoutLine line : items) {
            Product product = catalog.require(line.productId());
            long unitPrice = product.priceCents();
            long lineTotal = unitPrice * line.quantity();
            total += lineTotal;
            lines.add(new OrderLine(product.id(), product.name(), line.quantity(), unitPrice, lineTotal));
        }
        String createdAt = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS).toString();
        return new Order(orderIdGenerator.get(), user, createdAt, List.copyOf(lines), total);
    }

    private Optional<Order> findOrder(PersistentState state, String orderId) {
        return state.orders().stream().filter(o -> o.id().equals(orderId)).findFirst();
    }

    /** Stable fingerprint of "who buys what", used to detect idempotency key reuse. */
    private String fingerprint(String user, List<CheckoutLine> items) {
        StringBuilder builder = new StringBuilder(user.toLowerCase()).append('\n');
        items.stream()
                .sorted((a, b) -> a.productId().compareTo(b.productId()))
                .forEach(line -> builder.append(line.productId()).append('=').append(line.quantity()).append(';'));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(builder.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Current stock per product id (used by the catalog endpoint). */
    public Map<String, Integer> currentStock() {
        return repository.current().inventory();
    }

}
