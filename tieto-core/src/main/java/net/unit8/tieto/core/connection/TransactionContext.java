package net.unit8.tieto.core.connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Thread-local transaction context for standalone (non-Spring) usage.
 *
 * <p>Provides explicit transaction demarcation so that multiple repository
 * calls can share the same Connection and transaction.</p>
 *
 * <pre>{@code
 * TransactionContext.begin(dataSource);
 * try {
 *     orderRepo.save(order);
 *     TransactionContext.commit();
 * } catch (Exception e) {
 *     TransactionContext.rollback();
 *     throw e;
 * }
 * }</pre>
 */
public final class TransactionContext {

    private static final ThreadLocal<Connection> CURRENT_CONNECTION = new ThreadLocal<>();

    private TransactionContext() {}

    /**
     * Begins a new transaction by acquiring a Connection from the DataSource.
     *
     * @param dataSource the DataSource to get a connection from
     * @throws SQLException if a connection cannot be obtained
     * @throws IllegalStateException if a transaction is already active on this thread
     */
    public static void begin(DataSource dataSource) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        if (CURRENT_CONNECTION.get() != null) {
            throw new IllegalStateException("A transaction is already active on this thread");
        }
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        CURRENT_CONNECTION.set(conn);
    }

    /**
     * Commits the current transaction and releases the Connection.
     *
     * @throws SQLException if commit fails
     * @throws IllegalStateException if no transaction is active
     */
    public static void commit() throws SQLException {
        Connection conn = requireActive();
        try {
            conn.commit();
        } finally {
            conn.close();
            CURRENT_CONNECTION.remove();
        }
    }

    /**
     * Rolls back the current transaction and releases the Connection.
     *
     * @throws SQLException if rollback fails
     * @throws IllegalStateException if no transaction is active
     */
    public static void rollback() throws SQLException {
        Connection conn = requireActive();
        try {
            conn.rollback();
        } finally {
            conn.close();
            CURRENT_CONNECTION.remove();
        }
    }

    /**
     * Returns the Connection bound to the current thread, or null if
     * no transaction is active.
     */
    static Connection current() {
        return CURRENT_CONNECTION.get();
    }

    private static Connection requireActive() {
        Connection conn = CURRENT_CONNECTION.get();
        if (conn == null) {
            throw new IllegalStateException("No active transaction on this thread");
        }
        return conn;
    }
}
