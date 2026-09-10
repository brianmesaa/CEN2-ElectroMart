package com.electromart.service;

import com.electromart.domain.Product;
import com.electromart.persistence.InventoryDefaults;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single server side catalog: every distinct product offered by the home, search and
 * category pages, with a stable id, a name and a price in cents.
 *
 * <p>Prices are only ever read from here - never from the browser.</p>
 */
@Service
public class CatalogService implements InventoryDefaults {

    private static final String CATALOG_RESOURCE = "catalog.json";

    private final Map<String, Product> productsById;

    public CatalogService() {
        this(CATALOG_RESOURCE);
    }

    CatalogService(String resourceName) {
        this.productsById = Collections.unmodifiableMap(load(resourceName));
    }

    private Map<String, Product> load(String resourceName) {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = new ClassPathResource(resourceName).getInputStream()) {
            CatalogFile file = mapper.readValue(in, CatalogFile.class);
            if (file == null || file.products() == null || file.products().isEmpty()) {
                throw new IllegalStateException("Catalog resource '" + resourceName + "' contains no products.");
            }
            Map<String, Product> byId = new LinkedHashMap<>();
            for (Product product : file.products()) {
                if (product.id() == null || product.id().isBlank()) {
                    throw new IllegalStateException("Catalog contains a product without id.");
                }
                if (product.priceCents() <= 0) {
                    throw new IllegalStateException("Product '" + product.id() + "' has a non positive price.");
                }
                if (byId.put(product.id(), product) != null) {
                    throw new IllegalStateException("Duplicate product id in catalog: " + product.id());
                }
            }
            return byId;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read catalog resource '" + resourceName + "'", e);
        }
    }

    /** All catalog products in display order. */
    public List<Product> products() {
        return List.copyOf(productsById.values());
    }

    public Optional<Product> findById(String productId) {
        if (productId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(productsById.get(productId));
    }

    public boolean contains(String productId) {
        return productId != null && productsById.containsKey(productId);
    }

    /** Product for the given id or a {@link ValidationException} when it is unknown. */
    public Product require(String productId) {
        return findById(productId).orElseThrow(
                () -> new ValidationException("Unknown product: " + productId));
    }

    @Override
    public Map<String, Integer> initialStock() {
        Map<String, Integer> stock = new LinkedHashMap<>();
        productsById.values().forEach(p -> stock.put(p.id(), Math.max(0, p.stock())));
        return stock;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CatalogFile(List<Product> products) {
    }
}
