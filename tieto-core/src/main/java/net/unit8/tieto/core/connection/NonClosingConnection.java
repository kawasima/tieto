package net.unit8.tieto.core.connection;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;

/**
 * Wraps a Connection so that {@code close()} is a no-op while every other call
 * delegates to the target.
 *
 * <p>Used for the transaction-bound Connection: repository calls run inside a
 * transaction acquire it through {@link TietoDataSource#getConnection()} and
 * close it via try-with-resources, but the physical Connection must stay open
 * until the surrounding {@link TietoDataSource#inTransaction transaction}
 * commits or rolls back.</p>
 */
final class NonClosingConnection {

    private NonClosingConnection() {}

    static Connection wrap(Connection target) {
        return (Connection) Proxy.newProxyInstance(
                NonClosingConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        return null;   // the transaction owns the physical close
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
