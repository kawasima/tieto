package net.unit8.tieto.core.function;

/**
 * Runtime tuning for function invocation.
 *
 * <ul>
 *   <li>{@code queryTimeoutSeconds} &mdash; per-statement query timeout applied via
 *       {@link java.sql.Statement#setQueryTimeout(int)}. A non-zero default bounds a
 *       hung PostgreSQL function (lock wait, runaway plpgsql) so it cannot pin the
 *       calling thread and its pooled connection indefinitely; the driver cancels the
 *       statement and the call fails. {@code 0} disables the timeout.</li>
 *   <li>{@code fetchSize} &mdash; JDBC fetch size for {@code SETOF} reads, applied via
 *       {@link java.sql.Statement#setFetchSize(int)}. A positive value lets a large
 *       result set be read in batches rather than materialized at once. {@code 0}
 *       keeps the driver default. Note: pgjdbc only fetches by cursor inside a
 *       transaction (autocommit off), so this takes effect on the transactional path.</li>
 * </ul>
 *
 * <p>Defaults come from system properties via {@link #fromSystemProperties()} and can
 * be overridden per client through {@link net.unit8.tieto.core.TietoClientBuilder}.</p>
 */
public record InvocationConfig(int queryTimeoutSeconds, int fetchSize) {

    /** Default query timeout (seconds): a non-zero guard against a hung function. */
    public static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    public InvocationConfig {
        if (queryTimeoutSeconds < 0) {
            throw new IllegalArgumentException("queryTimeoutSeconds must be >= 0, was " + queryTimeoutSeconds);
        }
        if (fetchSize < 0) {
            throw new IllegalArgumentException("fetchSize must be >= 0, was " + fetchSize);
        }
    }

    /**
     * Reads defaults from the {@code tieto.query-timeout-seconds} (default
     * {@value #DEFAULT_QUERY_TIMEOUT_SECONDS}) and {@code tieto.fetch-size} (default 0)
     * system properties.
     */
    public static InvocationConfig fromSystemProperties() {
        return new InvocationConfig(
                Integer.getInteger("tieto.query-timeout-seconds", DEFAULT_QUERY_TIMEOUT_SECONDS),
                Integer.getInteger("tieto.fetch-size", 0));
    }
}
