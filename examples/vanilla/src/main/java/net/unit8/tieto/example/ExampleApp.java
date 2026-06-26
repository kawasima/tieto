package net.unit8.tieto.example;

import net.unit8.tieto.core.TietoClient;
import net.unit8.tieto.core.connection.TransactionContext;
import net.unit8.tieto.example.domain.Order;
import net.unit8.tieto.example.domain.OrderLine;
import net.unit8.tieto.example.domain.OrderRepository;
import net.unit8.tieto.example.domain.OrderSpec;
import net.unit8.tieto.example.domain.OrderStatus;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Example application demonstrating tieto usage.
 *
 * Prerequisites:
 *   cd tieto-example
 *   docker compose up -d
 */
public class ExampleApp {

    public static void main(String[] args) throws Exception {
        DataSource dataSource = createDataSource();
        TietoClient tieto = TietoClient.builder(dataSource).build();
        OrderRepository orderRepo = tieto.createRepository(OrderRepository.class);

        System.out.println("=== tieto Example App ===\n");

        // 1. Find existing order by ID (from test data)
        System.out.println("--- findById(1) ---");
        Optional<Order> order1 = orderRepo.findById(1L);
        order1.ifPresentOrElse(
                o -> {
                    System.out.println("  Order #" + o.id() + " customer=" + o.customerId()
                            + " status=" + o.status() + " created=" + o.createdAt());
                    o.lines().forEach(l -> System.out.println("    line: " + l.productId()
                            + " qty=" + l.quantity() + " price=" + l.unitPrice()));
                },
                () -> System.out.println("  Not found")
        );

        // 2. Find by customer ID (returns List)
        System.out.println("\n--- findByCustomerId(\"CUST-001\") ---");
        List<Order> custOrders = orderRepo.findByCustomerId("CUST-001");
        System.out.println("  Found " + custOrders.size() + " orders");
        custOrders.forEach(o -> System.out.println("  Order #" + o.id()
                + " status=" + o.status() + " lines=" + o.lines().size()));

        // 3. Save a new order (with transaction)
        System.out.println("\n--- save(new order) ---");
        TransactionContext.begin(dataSource);
        try {
            Order newOrder = new Order(
                    null,
                    "CUST-003",
                    List.of(
                            new OrderLine("PROD-X", 1, new BigDecimal("99.99")),
                            new OrderLine("PROD-Y", 2, new BigDecimal("24.50"))
                    ),
                    OrderStatus.PENDING,
                    LocalDateTime.now()
            );
            orderRepo.save(newOrder);
            TransactionContext.commit();
            System.out.println("  Saved successfully");
        } catch (Exception e) {
            TransactionContext.rollback();
            throw e;
        }

        // 4. Verify the saved order
        System.out.println("\n--- findByCustomerId(\"CUST-003\") ---");
        List<Order> newCustOrders = orderRepo.findByCustomerId("CUST-003");
        System.out.println("  Found " + newCustOrders.size() + " orders");
        newCustOrders.forEach(o -> {
            System.out.println("  Order #" + o.id() + " status=" + o.status());
            o.lines().forEach(l -> System.out.println("    line: " + l.productId()
                    + " qty=" + l.quantity() + " price=" + l.unitPrice()));
        });

        // 5. Update status
        System.out.println("\n--- updateStatus ---");
        Long savedOrderId = newCustOrders.getFirst().id();
        TransactionContext.begin(dataSource);
        try {
            orderRepo.updateStatus(savedOrderId, OrderStatus.CONFIRMED);
            TransactionContext.commit();
            System.out.println("  Updated order #" + savedOrderId + " to CONFIRMED");
        } catch (Exception e) {
            TransactionContext.rollback();
            throw e;
        }

        // 6. Verify status update
        Optional<Order> updated = orderRepo.findById(savedOrderId);
        updated.ifPresent(o -> System.out.println("  Verified: status=" + o.status()));

        // 7. findById for non-existent (returns Optional.empty)
        System.out.println("\n--- findById(9999) ---");
        Optional<Order> notFound = orderRepo.findById(9999L);
        System.out.println("  Result: " + (notFound.isEmpty() ? "Optional.empty" : notFound));

        // 8. Composable Specification queries (findBy)
        System.out.println("\n--- findBy: composable Specifications ---");

        runSpec(orderRepo, "HighValue(>=100)",
                new OrderSpec.HighValue(new BigDecimal("100")));

        runSpec(orderRepo, "And[ForCustomer(CUST-001), HasStatus(CONFIRMED)]",
                new OrderSpec.And(List.of(
                        new OrderSpec.ForCustomer("CUST-001"),
                        new OrderSpec.HasStatus(OrderStatus.CONFIRMED))));

        runSpec(orderRepo, "And[CreatedAfter(2024-06-05), Not(ForCustomer(CUST-002))]",
                new OrderSpec.And(List.of(
                        new OrderSpec.CreatedAfter(LocalDateTime.of(2024, 6, 5, 0, 0)),
                        new OrderSpec.Not(new OrderSpec.ForCustomer("CUST-002")))));

        runSpec(orderRepo, "Or[HasStatus(SHIPPED), HighValue(>=130)]",
                new OrderSpec.Or(List.of(
                        new OrderSpec.HasStatus(OrderStatus.SHIPPED),
                        new OrderSpec.HighValue(new BigDecimal("130")))));

        System.out.println("\n=== All operations completed successfully ===");
        System.exit(0);
    }

    private static void runSpec(OrderRepository repo, String label, OrderSpec spec) {
        List<Order> results = repo.findBy(spec);
        String ids = results.stream()
                .map(o -> "#" + o.id() + "(" + o.customerId() + "," + o.status() + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("(none)");
        System.out.println("  " + label + " -> " + results.size() + ": " + ids);
    }

    private static DataSource createDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[]{"localhost"});
        ds.setPortNumbers(new int[]{5432});
        ds.setDatabaseName("tieto_example");
        ds.setUser("tieto");
        ds.setPassword("tieto");
        return ds;
    }
}
