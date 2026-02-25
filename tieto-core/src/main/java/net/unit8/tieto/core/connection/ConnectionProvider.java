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
}
