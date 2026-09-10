package com.electromart.api;

import com.electromart.api.dto.ProductResponse;
import com.electromart.service.CatalogService;
import com.electromart.service.CheckoutService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/products} - the current catalog (id, name, price in cents, available stock).
 */
@RestController
@RequestMapping("/api")
public class ProductController {

    private final CatalogService catalog;
    private final CheckoutService checkoutService;

    public ProductController(CatalogService catalog, CheckoutService checkoutService) {
        this.catalog = catalog;
        this.checkoutService = checkoutService;
    }

    @GetMapping(value = "/products", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ProductResponse> products() {
        Map<String, Integer> stock = checkoutService.currentStock();
        return catalog.products().stream()
                .map(product -> ProductResponse.of(product, stock.getOrDefault(product.id(), 0)))
                .toList();
    }
}
