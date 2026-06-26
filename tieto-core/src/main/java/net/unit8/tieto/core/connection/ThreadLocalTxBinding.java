package net.unit8.tieto.core.connection;

import java.sql.Connection;

/**
 * A {@link TxBinding} backed by a {@link ThreadLocal}.
 *
 * <p>{@link #runBound} removes the binding in a {@code finally}, so a unit of
 * work that throws — or forgets to clean up — cannot leave a stale Connection
 * bound to a reused pool/web-container thread.</p>
 */
final class ThreadLocalTxBinding implements TxBinding {

    private final ThreadLocal<Connection> bound = new ThreadLocal<>();

    @Override
    public Connection current() {
        return bound.get();
    }

    @Override
    public <R> R runBound(Connection connection, TransactionalWork<R> work) {
        bound.set(connection);
        try {
            return work.execute();
        } finally {
            bound.remove();
        }
    }
}
