package net.unit8.tieto.core.connection;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Abstraction for acquiring a JDBC Connection.
 *
 * <p>In standalone mode, this is backed by a DataSource (with optional
 * thread-local transaction context). In Spring, this delegates to
 * DataSourceUtils to participate in Spring-managed transactions.</p>
 */
@FunctionalInterface
public interface ConnectionProvider {

    /**
     * Returns the current Connection.
     *
     * @return a JDBC Connection
     * @throws SQLException if a connection cannot be obtained
     */
    Connection getConnection() throws SQLException;

    /**
     * Releases a Connection previously obtained from {@link #getConnection()}.
     *
     * <p>The default closes the Connection, which suits providers that hand out
     * a fresh Connection per call. Providers that may return a Connection owned
     * by an enclosing transaction must override this to avoid closing it.</p>
     *
     * @param connection the Connection to release; may be {@code null}
     * @throws SQLException if the Connection cannot be released
     */
    default void releaseConnection(Connection connection) throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }
}
