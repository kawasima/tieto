package net.unit8.tieto.example.domain;

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
    Optional<Order> findById(Long id);

    /**
     * Find all orders for a given customer, ordered by creation date descending.
     */
    List<Order> findByCustomerId(String customerId);

    /**
     * Save a new order. Insert into orders and order_lines tables.
     * Generate the order ID using the orders_id_seq sequence.
     */
    void save(Order order);

    /**
     * Update the status of an order identified by the given ID.
     */
    void updateStatus(Long id, OrderStatus status);
}
