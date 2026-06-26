package net.unit8.tieto.core.connection;

import net.unit8.tieto.core.StubDataSource;
import net.unit8.tieto.core.TietoClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a function call returns its JDBC Connection to the pool instead
 * of leaking it, in both the non-transactional and transactional cases.
 *
 * <p>A {@link CountingDataSource} wraps the real PostgreSQL connections so the
 * test can assert how many are physically open at any moment.</p>
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
        try (Connection conn = newRealConnection();
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
        CountingDataSource counting = new CountingDataSource();
        TietoDataSource dataSource = new TietoDataSource(counting);
        TietoClient tieto = TietoClient.builder(dataSource).build();
        EchoRepository repo = tieto.createRepository(EchoRepository.class);

        dataSource.inTransaction(() -> {
            assertThat(repo.echo(1L)).isEqualTo(1L);
            assertThat(repo.echo(2L)).isEqualTo(2L);

            assertThat(counting.totalOpened())
                    .as("calls inside a transaction must reuse the one transactional connection")
                    .isEqualTo(1);
            assertThat(counting.currentlyOpen())
                    .as("the transactional connection must stay open between calls")
                    .isEqualTo(1);
            return null;
        });

        assertThat(counting.currentlyOpen())
                .as("commit must close the transactional connection")
                .isZero();
    }

    private static Connection newRealConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * A DataSource that hands out real PostgreSQL connections wrapped in a proxy
     * which decrements the open counter when closed.
     */
    static final class CountingDataSource extends StubDataSource {
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
            Connection real = newRealConnection();
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
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
