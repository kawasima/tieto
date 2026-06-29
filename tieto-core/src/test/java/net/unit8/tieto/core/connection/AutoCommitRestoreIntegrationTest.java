package net.unit8.tieto.core.connection;

import net.unit8.tieto.core.StubDataSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end guard against the silent data loss that occurs when
 * {@link TietoDataSource#inTransaction} leaves a Connection at
 * {@code autoCommit=false} and a pool hands it back without resetting it.
 *
 * <p>The "pool" here is a single physical Connection handed out wrapped so its
 * {@code close()} is a no-op and its state is never reset — the worst case. After
 * a transaction restores autoCommit, a subsequent non-transactional write must be
 * committed and visible to an independent connection.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class AutoCommitRestoreIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Test
    void aNonTransactionalWriteAfterATransactionIsPersisted() throws SQLException {
        try (Connection physical = newConnection()) {
            try (Statement stmt = physical.createStatement()) {
                stmt.execute("CREATE TABLE t (id int)");
            }

            // A non-resetting pool: every getConnection() hands back the same physical
            // Connection wrapped so close() neither closes it nor resets its state.
            TietoDataSource dataSource = new TietoDataSource(new StubDataSource() {
                @Override
                public Connection getConnection() {
                    return NonClosingConnection.wrap(physical);
                }
            });

            dataSource.inTransaction(() -> {
                insert(dataSource, 1);
                return null;
            });

            // Non-transactional write: relies on autoCommit having been restored to true.
            insert(dataSource, 2);

            // Verify from an independent connection, so only committed rows are seen.
            try (Connection verify = newConnection();
                    Statement stmt = verify.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT count(*) FROM t")) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("both the transactional and the later non-transactional write persist")
                        .isEqualTo(2);
            }
        }
    }

    private static void insert(TietoDataSource dataSource, int id) {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO t VALUES (" + id + ")");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
