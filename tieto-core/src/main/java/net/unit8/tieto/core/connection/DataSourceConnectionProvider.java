package net.unit8.tieto.core.connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * A {@link ConnectionProvider} backed by a {@link DataSource}.
 *
 * <p>If a {@link TransactionContext} is active on the current thread,
 * returns the transactional Connection. Otherwise, obtains a new
 * Connection from the DataSource.</p>
 */
public final class DataSourceConnectionProvider implements ConnectionProvider {

    private final DataSource dataSource;

    public DataSourceConnectionProvider(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection txConn = TransactionContext.current();
        if (txConn != null) {
            return txConn;
        }
        return dataSource.getConnection();
    }
}
