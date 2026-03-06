package net.unit8.tieto.example.domain;

import java.time.LocalDateTime;
import java.util.List;

public record Order(
        Long id,
        String customerId,
        List<OrderLine> lines,
        OrderStatus status,
        LocalDateTime createdAt
) {}
