package net.unit8.tieto.core.connection;

import net.unit8.tieto.core.TietoClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link net.unit8.tieto.core.function.FunctionInvoker} returns the
 * JDBC Connection after each non-transactional call, instead of leaking it.
 *
 * <p>A non-transactional repository call acquires a fresh Connection from the
 * DataSource. If that Connection is never closed, a bounded pool is exhausted
 * within a handful of calls. This test wraps the DataSource so it can count how
 * many physical Connections are currently open and asserts the count returns to
 * zero after a series of calls.</p>
 */
@Testcontainers
class ConnectionReleaseIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16");

    /** Repository whose single method maps to {@code echo_repository_echo_v1}. */
    interface EchoRepository {
        Long echo(Long id);
    }

    @BeforeAll
    static void deployFunction() throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE OR REPLACE FUNCTION echo_repository_echo_v1(p_id bigint)
                    RETURNS jsonb LANGUAGE sql AS $$ SELECT to_jsonb(p_id) $$
                    """);
        }
    }

    @Test
    void nonTransactionalCallsReleaseTheirConnections() {
        CountingDataSource dataSource = new CountingDataSource();
        TietoClient tieto = TietoClient.builder(dataSource).build();
        EchoRepository repo = tieto.createRepository(EchoRepository.class);

        for (long i = 1; i <= 5; i++) {
            assertThat(repo.echo(i)).isEqualTo(i);
        }

        assertThat(dataSource.currentlyOpen())
                .as("every non-transactional call must return its connection to the pool")
                .isZero();
    }

    @Test
    void transactionalCallsShareOneConnectionAndKeepItOpenUntilCommit() throws SQLException {
        CountingDataSource dataSource = new CountingDataSource();
        TietoClient tieto = TietoClient.builder(dataSource).build();
        EchoRepository repo = tieto.createRepository(EchoRepository.class);

        TransactionContext.begin(dataSource);
        try {
            assertThat(repo.echo(1L)).isEqualTo(1L);
            assertThat(repo.echo(2L)).isEqualTo(2L);

            assertThat(dataSource.totalOpened())
                    .as("calls inside a transaction must reuse the one transactional connection")
                    .isEqualTo(1);
            assertThat(dataSource.currentlyOpen())
                    .as("the transactional connection must stay open between calls")
                    .isEqualTo(1);

            TransactionContext.commit();
        } catch (RuntimeException e) {
            TransactionContext.rollback();
            throw e;
        }

        assertThat(dataSource.currentlyOpen())
                .as("commit must close the transactional connection")
                .isZero();
    }

    /**
     * A DataSource that hands out real PostgreSQL connections wrapped in a proxy
     * which decrements the open counter when closed.
     */
    static final class CountingDataSource implements DataSource {
        private final AtomicInteger open = new AtomicInteger();
        private final AtomicInteger totalOpened = new AtomicInteger();

        int currentlyOpen() {
            return open.get();
        }

        int totalOpened() {
            return totalOpened.get();
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection real = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            open.incrementAndGet();
            totalOpened.incrementAndGet();
            AtomicBoolean counted = new AtomicBoolean(true);
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("close".equals(method.getName()) && counted.compareAndSet(true, false)) {
                            open.decrementAndGet();
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
        @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
        @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { throw new UnsupportedOperationException(); }
        @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
