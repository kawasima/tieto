package net.unit8.tieto.example.domain;

import java.math.BigDecimal;

public record OrderLine(
        String productId,
        int quantity,
        BigDecimal unitPrice
) {}
