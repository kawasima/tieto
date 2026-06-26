package net.unit8.tieto.example.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Composable search Specification for orders.
 * Leaves describe a single condition; And/Or/Not compose them into a tree.
 */
public sealed interface OrderSpec
        permits OrderSpec.And, OrderSpec.Or, OrderSpec.Not,
                OrderSpec.ForCustomer, OrderSpec.HasStatus,
                OrderSpec.CreatedAfter, OrderSpec.HighValue {

    record And(List<OrderSpec> specs) implements OrderSpec {}

    record Or(List<OrderSpec> specs) implements OrderSpec {}

    record Not(OrderSpec spec) implements OrderSpec {}

    record ForCustomer(String customerId) implements OrderSpec {}

    record HasStatus(OrderStatus status) implements OrderSpec {}

    record CreatedAfter(LocalDateTime t) implements OrderSpec {}

    record HighValue(BigDecimal min) implements OrderSpec {}
}
