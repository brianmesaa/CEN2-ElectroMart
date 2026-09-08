package com.electromart.persistence;

import java.util.Map;

/**
 * Supplies the stock levels used when the state file has to be created (or when the catalog
 * gained a product that is not present in an existing state file).
 *
 * <p>Declaring the interface here keeps the persistence layer independent of the catalog
 * implementation; the service layer provides it.</p>
 */
public interface InventoryDefaults {

    /** Product id to initial stock. */
    Map<String, Integer> initialStock();
}
