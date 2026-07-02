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
 * End-to-end guard against silent write loss on the <em>non-transactional</em> path when the
 * pool's baseline is {@code autoCommit=false}. Without the {@link AutoCommitConnection}
 * wrapping, a call made outside {@link TietoDataSource#inTransaction} runs in an implicit
 * transaction that {@code close()} never commits, so the write is rolled back.
 */
@Testcontainers(disabledWithoutDocker = true)
class NonTransactionalDurabilityIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Test
    void aNonTransactionalWriteOnAnAutoCommitFalsePoolIsPersisted() throws SQLException {
        try (Connection setup = newConnection();
                Statement stmt = setup.createStatement()) {
            stmt.execute("CREATE TABLE t (id int)");
        }

        // A pool configured with autoCommit=false as its baseline: every getConnection()
        // hands out a fresh physical connection already at autoCommit=false.
        TietoDataSource dataSource = new TietoDataSource(new StubDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                Connection conn = newConnection();
                conn.setAutoCommit(false);
                return conn;
            }
        });

        insert(dataSource, 1);   // non-transactional write

        // Verify from an independent connection, so only committed rows are seen.
        try (Connection verify = newConnection();
                Statement stmt = verify.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT count(*) FROM t")) {
            rs.next();
            assertThat(rs.getInt(1))
                    .as("a non-transactional write on an autoCommit=false pool persists")
                    .isEqualTo(1);
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
