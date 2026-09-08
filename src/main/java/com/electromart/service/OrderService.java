package com.electromart.service;

import com.electromart.config.ElectroMartProperties;
import com.electromart.domain.Order;
import com.electromart.persistence.StateRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Read access to the stored orders.
 */
@Service
public class OrderService {

    private final StateRepository repository;
    private final ElectroMartProperties properties;

    public OrderService(StateRepository repository, ElectroMartProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Orders of the given user. The prototype's admin account ({@code bmesa@gmail.com})
     * keeps seeing every order.
     */
    public List<Order> ordersOf(String user) {
        if (user == null || user.isBlank()) {
            throw new ValidationException("A user e-mail address is required.");
        }
        String requested = user.trim();
        List<Order> all = repository.current().orders();
        if (isAdmin(requested)) {
            return all;
        }
        return all.stream().filter(o -> o.user().equalsIgnoreCase(requested)).toList();
    }

    public Optional<Order> findById(String orderId) {
        return repository.current().orders().stream().filter(o -> o.id().equals(orderId)).findFirst();
    }

    public boolean isAdmin(String user) {
        return user != null && user.trim().equalsIgnoreCase(properties.getAdminUser());
    }
}
