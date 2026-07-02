package net.unit8.tieto.core.connection;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Wraps a Connection borrowed <em>outside</em> a transaction whose pool baseline is
 * {@code autoCommit=false}, switching it to {@code autoCommit=true} for the call and
 * restoring {@code autoCommit=false} just before the physical {@code close()}.
 *
 * <p>tieto-core invokes every function as
 * {@code try (Connection c = dataSource.getConnection()) { ... }} and never calls
 * {@code commit()} on the non-transactional path — each call is meant to be its own
 * atomic unit. A pool whose baseline is {@code autoCommit=false} (a legal HikariCP
 * setting) would otherwise run that call in an implicit transaction that {@code close()}
 * never commits, silently discarding the write and leaking connections left "idle in
 * transaction". Putting the connection in autoCommit mode makes the single statement
 * commit on success and roll back on failure, exactly as the non-transactional path
 * assumes; restoring the pool's baseline on close keeps a non-resetting pool intact,
 * mirroring {@link TietoDataSource#inTransaction}'s flip-and-restore.</p>
 *
 * <p>An {@code autoCommit=true} connection needs none of this and is returned unwrapped,
 * so the common case pays nothing. Cursor-based streaming for {@code SETOF} reads still
 * requires an explicit {@link TietoDataSource#inTransaction transaction} (pgjdbc only
 * fetches by cursor inside one), independent of the pool's baseline.</p>
 */
final class AutoCommitConnection {

    private AutoCommitConnection() {}

    static Connection wrap(Connection target) throws SQLException {
        try {
            target.setAutoCommit(true);
        } catch (SQLException e) {
            // Never leak the borrowed connection if we cannot put it in autoCommit mode.
            try {
                target.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        return (Connection) Proxy.newProxyInstance(
                AutoCommitConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        try {
                            target.setAutoCommit(false);   // restore the pool's baseline
                        } catch (SQLException | RuntimeException ignored) {
                            // Best-effort restore; the physical close below still runs. If this
                            // rare failure leaves the connection at autoCommit=true, a pool that
                            // resets connection state on return (e.g. HikariCP) corrects it; the
                            // window matches inTransaction's own best-effort autoCommit restore.
                        }
                        target.close();
                        return null;
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
