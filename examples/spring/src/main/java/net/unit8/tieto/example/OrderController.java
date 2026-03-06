package net.unit8.tieto.example;

import net.unit8.tieto.example.domain.Order;
import net.unit8.tieto.example.domain.OrderLine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> findById(@PathVariable Long id) {
        return orderService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(params = "customerId")
    public List<Order> findByCustomerId(@RequestParam String customerId) {
        return orderService.findByCustomerId(customerId);
    }

    @PostMapping
    public ResponseEntity<Void> create(@RequestBody CreateOrderRequest request) {
        orderService.placeOrder(request.customerId(), request.lines());
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Void> updateStatus(@PathVariable Long id,
                                             @RequestBody UpdateStatusRequest request) {
        orderService.updateStatus(id, request.status());
        return ResponseEntity.ok().build();
    }

    public record CreateOrderRequest(String customerId, List<OrderLine> lines) {}

    public record UpdateStatusRequest(String status) {}
}
