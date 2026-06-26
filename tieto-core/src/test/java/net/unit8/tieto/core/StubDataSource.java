package net.unit8.tieto.core;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Minimal {@link DataSource} stub for tests. Subclasses typically override only
 * {@link #getConnection()}; every other operation throws
 * {@link UnsupportedOperationException}, except {@link #getLoginTimeout()} (returns
 * {@code 0}) and {@link #isWrapperFor(Class)} (returns {@code false}).
 */
public class StubDataSource implements DataSource {

    /**
     * A stub that opens a fresh DriverManager connection per call — for integration
     * tests that point at a Testcontainers database.
     */
    public static StubDataSource opening(String url, String user, String password) {
        return new StubDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url, user, password);
            }
        };
    }

    @Override
    public Connection getConnection() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
    @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
    @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() { throw new UnsupportedOperationException(); }
    @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
}
