package net.unit8.tieto.core.connection;

import java.sql.Connection;

/**
 * Binds the current transaction's Connection for the dynamic extent of a unit
 * of work, so that {@link TietoDataSource#getConnection()} can hand it back to
 * repository calls running inside the transaction.
 *
 * <p>This is the seam that decides <em>how</em> the binding is carried across
 * the call stack. The default {@link ThreadLocalTxBinding} uses a
 * {@link ThreadLocal}; a {@code ScopedValue}-based implementation can replace it
 * on Java 25+ without touching {@link TietoDataSource}.</p>
 */
interface TxBinding {

    /**
     * Returns the Connection bound to the current execution, or {@code null} if
     * no transaction is active.
     */
    Connection current();

    /**
     * Binds {@code connection} for the duration of {@code work} and runs it,
     * unbinding unconditionally when the work returns or throws. This method
     * owns only the binding lifecycle, not the Connection lifecycle.
     */
    <R> R runBound(Connection connection, TransactionalWork<R> work);
}
