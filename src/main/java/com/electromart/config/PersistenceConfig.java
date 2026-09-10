package com.electromart.config;

import com.electromart.persistence.JsonStateStore;
import com.electromart.persistence.StateRepository;
import com.electromart.service.CatalogService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Wires the persistence layer: one JSON state file whose location comes from
 * {@code ELECTROMART_DATA_FILE} (default {@code data/electromart-state.json}).
 *
 * <p>The state file is read (and, when missing, created from the catalog) while the
 * application context starts, so a corrupted file makes startup fail with a clear error
 * instead of silently replacing the stored data.</p>
 */
@Configuration
public class PersistenceConfig {

    @Bean
    public JsonStateStore jsonStateStore(ElectroMartProperties properties) {
        return new JsonStateStore(Path.of(properties.getDataFile()));
    }

    @Bean
    public StateRepository stateRepository(JsonStateStore store, CatalogService catalog) {
        return new StateRepository(store, catalog);
    }
}
