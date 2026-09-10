package com.electromart.service;

import com.electromart.domain.InventoryAdjustmentRecord;
import com.electromart.domain.Product;
import com.electromart.persistence.PersistentState;
import com.electromart.persistence.StateRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages restocking and inventory adjustments for catalog products.
 *
 * <ul>
 *   <li>Only positive whole number additions are permitted.</li>
 *   <li>Uses StateRepository.transact() to share state and locking with checkout.</li>
 *   <li>Idempotency keys ensure retrying an adjustment returns the original result without re-adding.</li>
 * </ul>
 */
@Service
public class InventoryAdjustmentService {

    private final CatalogService catalog;
    private final StateRepository repository;

    public InventoryAdjustmentService(CatalogService catalog, StateRepository repository) {
        this.catalog = catalog;
        this.repository = repository;
    }

    public InventoryAdjustmentResult adjustStock(String idempotencyKey, String productId, Long quantity) {
        String key = requireIdempotencyKey(idempotencyKey);
        String prodId = requireProductId(productId);
        long qty = requireQuantity(quantity);

        return repository.transact(state -> {
            InventoryAdjustmentRecord existing = state.adjustments().get(key);
            if (existing != null) {
                if (!existing.productId().equals(prodId) || existing.quantityAdded() != qty) {
                    throw new IdempotencyConflictException(key,
                            "Idempotency-Key '" + key + "' was already used for a different inventory adjustment.");
                }
                return StateRepository.TransactionResult.unchanged(
                        new InventoryAdjustmentResult(
                                existing.productId(),
                                existing.productName(),
                                existing.quantityAdded(),
                                existing.resultingStock(),
                                true));
            }

            Product product = catalog.require(prodId);
            int currentStock = state.stockOf(prodId);

            if (qty > (long) Integer.MAX_VALUE - currentStock) {
                throw new StockOverflowException("Adding " + qty + " to current stock of " + currentStock
                        + " would exceed maximum stock (" + Integer.MAX_VALUE + ").");
            }

            int newStock = (int) (currentStock + qty);

            Map<String, Integer> nextInventory = new LinkedHashMap<>(state.inventory());
            nextInventory.put(prodId, newStock);

            InventoryAdjustmentRecord record = new InventoryAdjustmentRecord(
                    key, prodId, product.name(), (int) qty, newStock);
            Map<String, InventoryAdjustmentRecord> nextAdjustments = new LinkedHashMap<>(state.adjustments());
            nextAdjustments.put(key, record);

            PersistentState next = new PersistentState(
                    state.version(), nextInventory, state.orders(), state.idempotency(), nextAdjustments);

            return StateRepository.TransactionResult.changed(
                    next,
                    new InventoryAdjustmentResult(prodId, product.name(), (int) qty, newStock, false));
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

    private String requireProductId(String productId) {
        if (productId == null || productId.isBlank()) {
            throw new ValidationException("productId is required.");
        }
        String id = productId.trim();
        if (!catalog.contains(id)) {
            throw new ValidationException("Unknown product: " + id);
        }
        return id;
    }

    private long requireQuantity(Long quantity) {
        if (quantity == null) {
            throw new ValidationException("quantity is required.");
        }
        if (quantity <= 0) {
            throw new ValidationException("quantity must be a positive integer.");
        }
        return quantity;
    }
}
