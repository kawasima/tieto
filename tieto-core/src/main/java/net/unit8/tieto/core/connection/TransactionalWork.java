package net.unit8.tieto.core.connection;

/**
 * A unit of work executed inside a {@link TietoDataSource#inTransaction transaction}.
 *
 * <p>Repository calls inside the work share one Connection and one transaction.
 * Returning normally commits; throwing rolls back.</p>
 *
 * @param <R> the result type
 */
@FunctionalInterface
public interface TransactionalWork<R> {

    /**
     * Runs the transactional work and returns its result.
     *
     * @return the result of the work
     */
    R execute();
}
