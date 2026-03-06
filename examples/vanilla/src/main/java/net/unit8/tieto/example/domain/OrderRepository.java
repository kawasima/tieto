package net.unit8.tieto.example.domain;

import net.unit8.tieto.core.annotation.FunctionVersion;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Order aggregates.
 * tieto will proxy this interface and delegate to PostgreSQL functions.
 */
public interface OrderRepository {

    /**
     * Find an order by its ID.
     * Query the orders table joined with order_lines.
     * Return the full aggregate as nested JSON.
     */
    @FunctionVersion(1)
    Optional<Order> findById(Long id);

    /**
     * Find all orders for a given customer, ordered by creation date descending.
     */
    @FunctionVersion(1)
    List<Order> findByCustomerId(String customerId);

    /**
     * Save a new order. Insert into orders and order_lines tables.
     * Generate the order ID using the orders_id_seq sequence.
     */
    @FunctionVersion(1)
    void save(Order order);

    /**
     * Update the status of an order identified by the given ID.
     */
    @FunctionVersion(1)
    void updateStatus(Long id, OrderStatus status);
}
