package net.unit8.tieto.example;

import net.unit8.tieto.example.domain.Order;
import net.unit8.tieto.example.domain.OrderLine;
import net.unit8.tieto.example.domain.OrderRepository;
import net.unit8.tieto.example.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the auto-wired {@link OrderService} / {@link OrderRepository} through the real Spring Boot
 * context (tieto auto-configuration, {@code @Transactional}) against a Testcontainers PostgreSQL
 * loaded with the committed schema + functions + seed.
 *
 * <p>These tests are the acceptance gate for the committed {@code order_repository.sql}. Beyond the
 * per-method behaviour, they verify the point of the Spring integration: a tieto call inside a
 * {@code @Transactional} boundary joins that transaction, so a rollback undoes its write.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OrderServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withCopyFileToContainer(MountableFile.forClasspathResource("db/01_schema.sql"),
                    "/docker-entrypoint-initdb.d/01_schema.sql")
            .withCopyFileToContainer(MountableFile.forClasspathResource("db/order_repository.sql"),
                    "/docker-entrypoint-initdb.d/02_functions.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    OrderService orderService;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    DataSource dataSource;

    @BeforeEach
    void resetSeed() throws SQLException, IOException {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE orders RESTART IDENTITY CASCADE");
            stmt.execute(classpath("db/02_testdata.sql"));
        }
    }

    @Test
    void findByIdReturnsTheSeededAggregate() {
        Order order = orderService.findById(1L).orElseThrow();
        assertThat(order.customerId()).isEqualTo("CUST-001");
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.lines()).extracting(OrderLine::productId).containsExactly("PROD-A", "PROD-B");
    }

    @Test
    void placeOrderPersistsThroughTheTransactionalService() {
        orderService.placeOrder("CUST-003",
                List.of(new OrderLine("PROD-X", 1, new BigDecimal("99.99"))));

        List<Order> orders = orderService.findByCustomerId("CUST-003");
        assertThat(orders).hasSize(1);
        assertThat(orders.getFirst().status()).isEqualTo(OrderStatus.PENDING);
        assertThat(orders.getFirst().lines()).extracting(OrderLine::productId).containsExactly("PROD-X");
    }

    @Test
    void updateStatusIsReflectedOnRead() {
        orderService.updateStatus(2L, "SHIPPED");
        assertThat(orderService.findById(2L).orElseThrow().status()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void findByCustomerIdReturnsAllOrdersNewestFirst() {
        // CUST-001 has two seeded orders; they must come back created_at DESC (id 2 before id 1).
        assertThat(orderService.findByCustomerId("CUST-001")).extracting(Order::id).containsExactly(2L, 1L);
    }

    @Test
    void findByIdReturnsEmptyForAMissingOrder() {
        assertThat(orderService.findById(999L)).isEmpty();
    }

    @Test
    void saveWithNullLinesStoresAnOrderWithNoLines() {
        // A null line list serializes as "lines":null; save must treat it as no lines, not error.
        orderRepository.save(new Order(null, "CUST-NOLINES", null, OrderStatus.PENDING,
                LocalDateTime.of(2024, 7, 3, 9, 0)));

        List<Order> saved = orderService.findByCustomerId("CUST-NOLINES");
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().lines()).isEmpty();
    }

    @Test
    void aTietoWriteRollsBackWithTheSurroundingTransaction() {
        // The point of tieto-spring: the save runs on the transaction's connection, so when the
        // @Transactional boundary rolls back, the write is undone — not left committed.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            orderRepository.save(new Order(null, "CUST-ROLLBACK",
                    List.of(new OrderLine("PROD-Z", 1, new BigDecimal("10.00"))),
                    OrderStatus.PENDING, null));
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(orderRepository.findByCustomerId("CUST-ROLLBACK"))
                .as("the save joined the transaction and was rolled back").isEmpty();
    }

    private static String classpath(String resource) throws IOException {
        try (InputStream in = OrderServiceIntegrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("resource not found: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
