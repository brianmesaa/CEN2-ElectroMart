package com.electromart.api;

import com.electromart.domain.Order;
import com.electromart.service.OrderService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code GET /api/orders?user=<email>} - the orders of one user
 * (the prototype admin {@code bmesa@gmail.com} sees all of them).
 */
@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping(value = "/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Order> orders(@RequestParam(value = "user", required = false) String user) {
        return orderService.ordersOf(user);
    }
}
