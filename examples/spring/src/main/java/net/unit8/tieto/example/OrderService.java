package net.unit8.tieto.example;

import net.unit8.tieto.example.domain.Order;
import net.unit8.tieto.example.domain.OrderLine;
import net.unit8.tieto.example.domain.OrderRepository;
import net.unit8.tieto.example.domain.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public Optional<Order> findById(Long id) {
        return orderRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Order> findByCustomerId(String customerId) {
        return orderRepository.findByCustomerId(customerId);
    }

    @Transactional
    public void placeOrder(String customerId, List<OrderLine> lines) {
        Order order = new Order(null, customerId, lines, OrderStatus.PENDING, LocalDateTime.now());
        orderRepository.save(order);
    }

    @Transactional
    public void updateStatus(Long orderId, String status) {
        orderRepository.updateStatus(orderId, OrderStatus.valueOf(status));
    }
}
