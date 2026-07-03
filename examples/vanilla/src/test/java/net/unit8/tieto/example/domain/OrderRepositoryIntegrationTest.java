package net.unit8.tieto.example.domain;

import net.unit8.tieto.core.TietoClient;
import net.unit8.tieto.core.connection.TietoDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural tests for {@link OrderRepository}, driven through the real tieto proxy against a
 * PostgreSQL container loaded with the committed schema + functions + seed.
 *
 * <p>These tests are the acceptance gate for the generated/committed {@code 03_functions.sql}:
 * the developer writes them to express what each method must do, and the SQL — whether authored by
 * AI or by hand — is accepted only if they pass. The schema and functions are loaded once by the
 * container's init (exactly as production does via docker-compose); the seed is reset before each
 * test so the assertions run against a known, isolated fixture.</p>
 */
@Testcontainers
class OrderRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withCopyFileToContainer(MountableFile.forClasspathResource("db/01_schema.sql"),
                    "/docker-entrypoint-initdb.d/01_schema.sql")
            .withCopyFileToContainer(MountableFile.forClasspathResource("db/order_repository.sql"),
                    "/docker-entrypoint-initdb.d/02_functions.sql");

    private static TietoDataSource dataSource;
    private static OrderRepository repo;

    @BeforeAll
    static void wireRepository() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = new TietoDataSource(ds);
        repo = TietoClient.builder(dataSource).build().createRepository(OrderRepository.class);
    }

    @BeforeEach
    void resetSeed() throws SQLException, IOException {
        try (Connection conn = admin(); Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE orders RESTART IDENTITY CASCADE");
            stmt.execute(classpath("db/02_testdata.sql"));
        }
    }

    // --- findById ---------------------------------------------------------------------------

    @Test
    void findByIdReturnsTheAggregateWithItsLines() {
        Order order = repo.findById(1L).orElseThrow();
        assertThat(order.customerId()).isEqualTo("CUST-001");
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.lines()).extracting(OrderLine::productId).containsExactly("PROD-A", "PROD-B");
        assertThat(order.lines().getFirst().quantity()).isEqualTo(2);
    }

    @Test
    void findByIdReturnsEmptyForAMissingOrder() {
        assertThat(repo.findById(9999L)).isEmpty();
    }

    // --- findByCustomerId -------------------------------------------------------------------

    @Test
    void findByCustomerIdReturnsAllMatchesNewestFirst() {
        List<Order> orders = repo.findByCustomerId("CUST-001");
        // Two seeded CUST-001 orders, ordered by creation date descending (id 2 is newer than id 1).
        assertThat(orders).extracting(Order::id).containsExactly(2L, 1L);
        assertThat(orders).allSatisfy(o -> assertThat(o.customerId()).isEqualTo("CUST-001"));
    }

    // --- save (round-trip) ------------------------------------------------------------------

    @Test
    void saveThenReadBackRoundTrips() throws SQLException {
        Order neu = new Order(null, "CUST-003",
                List.of(new OrderLine("PROD-X", 1, new BigDecimal("99.99")),
                        new OrderLine("PROD-Y", 2, new BigDecimal("24.50"))),
                OrderStatus.PENDING, LocalDateTime.of(2024, 7, 1, 12, 0));
        inTransaction(() -> repo.save(neu));

        List<Order> saved = repo.findByCustomerId("CUST-003");
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().status()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.getFirst().lines()).extracting(OrderLine::productId)
                .containsExactly("PROD-X", "PROD-Y");
    }

    @Test
    void saveWithNullLinesStoresAnOrderWithNoLines() throws SQLException {
        // The domain allows a null line list; it serializes as "lines":null (not absent), which
        // save() must treat as "no lines" rather than erroring on jsonb_array_length of a scalar.
        Order neu = new Order(null, "CUST-004", null, OrderStatus.PENDING, LocalDateTime.of(2024, 7, 2, 9, 0));
        inTransaction(() -> repo.save(neu));

        List<Order> saved = repo.findByCustomerId("CUST-004");
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().lines()).isEmpty();
    }

    // --- updateStatus -----------------------------------------------------------------------

    @Test
    void updateStatusIsReflectedOnRead() throws SQLException {
        inTransaction(() -> repo.updateStatus(2L, OrderStatus.SHIPPED));
        assertThat(repo.findById(2L).orElseThrow().status()).isEqualTo(OrderStatus.SHIPPED);
    }

    // --- findBy (composable Specification) --------------------------------------------------
    // The assertions are the oracle: each result must satisfy the specification.

    @Test
    void findByForCustomerReturnsOnlyThatCustomersOrders() {
        List<Order> orders = repo.findBy(new OrderSpec.ForCustomer("CUST-001"));
        assertThat(orders).isNotEmpty()
                .allSatisfy(o -> assertThat(o.customerId()).isEqualTo("CUST-001"));
    }

    @Test
    void findByHighValueReturnsOrdersWhoseLineTotalMeetsTheThreshold() {
        // order 1 = 2*29.99 + 1*49.99 = 109.97 ; order 3 = 1*29.99 + 5*14.99 = 104.94 ; order 2 = 29.97
        List<Order> orders = repo.findBy(new OrderSpec.HighValue(new BigDecimal("100")));
        assertThat(orders).extracting(Order::id).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void findByAndCombinesLeavesConjunctively() {
        List<Order> orders = repo.findBy(new OrderSpec.And(List.of(
                new OrderSpec.ForCustomer("CUST-001"),
                new OrderSpec.HasStatus(OrderStatus.CONFIRMED))));
        assertThat(orders).extracting(Order::id).containsExactly(1L);
    }

    @Test
    void findByNotAndCreatedAfterCompose() {
        List<Order> orders = repo.findBy(new OrderSpec.And(List.of(
                new OrderSpec.CreatedAfter(LocalDateTime.of(2024, 6, 5, 0, 0)),
                new OrderSpec.Not(new OrderSpec.ForCustomer("CUST-002")))));
        assertThat(orders).extracting(Order::id).containsExactly(2L);
    }

    @Test
    void findByOrIsDisjunctive() {
        List<Order> orders = repo.findBy(new OrderSpec.Or(List.of(
                new OrderSpec.HasStatus(OrderStatus.SHIPPED),
                new OrderSpec.HighValue(new BigDecimal("130")))));
        assertThat(orders).extracting(Order::id).containsExactly(3L);
    }

    @Test
    void findByASpecValueContainingAQuoteIsBoundNotInjected() {
        // A leaf value with SQL metacharacters must be treated as data, not SQL. It simply matches
        // nothing (no such customer) rather than erroring or injecting.
        List<Order> orders = repo.findBy(new OrderSpec.ForCustomer("x'; DROP TABLE orders;--"));
        assertThat(orders).isEmpty();
        // The table is still there and still seeded.
        assertThat(repo.findById(1L)).isPresent();
    }

    // --- helpers ----------------------------------------------------------------------------

    private interface Work {
        void run();
    }

    private static void inTransaction(Work work) throws SQLException {
        dataSource.inTransaction(() -> {
            work.run();
            return null;
        });
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String classpath(String resource) throws IOException {
        try (InputStream in = OrderRepositoryIntegrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("resource not found: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
