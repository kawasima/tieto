package net.unit8.tieto.core.connection;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A transaction-aware {@link DataSource} for standalone (non-Spring) use.
 *
 * <p>tieto's core invokes every function as
 * {@code try (Connection c = dataSource.getConnection()) { ... }}, so the
 * "close it, or leave it open?" decision lives entirely in the Connection this
 * DataSource hands out:</p>
 * <ul>
 *   <li>Outside a transaction, {@link #getConnection()} returns a fresh
 *       Connection from the target DataSource; try-with-resources closes it.</li>
 *   <li>Inside {@link #inTransaction}, it returns the transaction's Connection
 *       wrapped so that {@code close()} is a no-op; the physical Connection is
 *       committed/rolled back and closed by {@code inTransaction} itself.</li>
 * </ul>
 *
 * <p>Transaction demarcation is structured: {@link #inTransaction} owns the
 * Connection's whole lifecycle, so there is no way to leak it by forgetting to
 * commit or roll back.</p>
 *
 * <pre>{@code
 * TietoDataSource dataSource = new TietoDataSource(plainDataSource);
 * TietoClient tieto = TietoClient.builder(dataSource).build();
 * OrderRepository repo = tieto.createRepository(OrderRepository.class);
 *
 * dataSource.inTransaction(() -> {
 *     repo.save(order);
 *     return null;
 * });
 * }</pre>
 */
public final class TietoDataSource implements DataSource {

    private final DataSource target;
    private final TxBinding binding;

    public TietoDataSource(DataSource target) {
        this(target, new ThreadLocalTxBinding());
    }

    TietoDataSource(DataSource target, TxBinding binding) {
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.binding = Objects.requireNonNull(binding, "binding must not be null");
    }

    /**
     * Runs {@code work} in a single transaction: all repository calls inside it
     * share one Connection. Returning normally commits; throwing rolls back.
     * A nested call joins the existing transaction rather than starting a new one.
     *
     * @param work the transactional work
     * @param <R> the result type
     * @return the result of the work
     * @throws SQLException if the transaction cannot be started, committed, or rolled back
     */
    public <R> R inTransaction(TransactionalWork<R> work) throws SQLException {
        if (binding.current() != null) {
            return work.execute();   // join the enclosing transaction
        }
        Connection conn = target.getConnection();
        try {
            conn.setAutoCommit(false);
            R result = binding.runBound(conn, work);
            conn.commit();
            return result;
        } catch (Throwable t) {
            try {
                conn.rollback();
            } catch (SQLException e) {
                t.addSuppressed(e);
            }
            throw t;
        } finally {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // The transaction is finished and the Connection is being
                // discarded; a close failure here is not actionable.
            }
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection bound = binding.current();
        return bound != null ? NonClosingConnection.wrap(bound) : target.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection bound = binding.current();
        return bound != null ? NonClosingConnection.wrap(bound) : target.getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return target.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        target.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        target.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return target.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return iface.isInstance(this) ? iface.cast(this) : target.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || target.isWrapperFor(iface);
    }
}
