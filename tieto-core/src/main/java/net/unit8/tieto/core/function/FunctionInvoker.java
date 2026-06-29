package net.unit8.tieto.core.function;

import net.unit8.tieto.core.exception.FunctionCallException;
import net.unit8.tieto.core.mapper.DomainMapper;
import net.unit8.tieto.core.mapper.MapperRegistry;
import net.unit8.tieto.core.proxy.MethodMetadata;
import net.unit8.tieto.core.proxy.ParameterInfo;
import net.unit8.tieto.core.proxy.ReturnTypeHandler;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Executes PostgreSQL functions via JDBC.
 *
 * <p>Uses {@code SELECT * FROM function_name(?, ...)} syntax rather than
 * CallableStatement, which works naturally with SETOF return types and JSONB.</p>
 */
public final class FunctionInvoker {

    private static final Logger log = LoggerFactory.getLogger(FunctionInvoker.class);

    /**
     * Calls slower than this (ms) are logged at WARN. Override with the
     * {@code tieto.slow-call-threshold-ms} system property; 0 or negative disables it.
     */
    private static final long SLOW_CALL_THRESHOLD_MS =
            Long.getLong("tieto.slow-call-threshold-ms", 1000L);

    private FunctionInvoker() {}

    /**
     * Invokes a PostgreSQL function and processes the result.
     *
     * <p>The Connection is acquired and released via try-with-resources. Whether
     * {@code close()} physically closes the Connection or hands it back to an
     * enclosing transaction is decided by the {@link DataSource} (e.g.
     * {@link net.unit8.tieto.core.connection.TietoDataSource} or Spring's
     * {@code TransactionAwareDataSourceProxy}), not here.</p>
     */
    public static Object invoke(
            DataSource dataSource,
            String functionName,
            MethodMetadata metadata,
            Object[] args,
            MapperRegistry mapperRegistry,
            InvocationConfig config) {

        String sql = buildSql(functionName, metadata.parameters().size());

        if (log.isDebugEnabled()) {
            // Argument shapes only (types/cardinality), never values — they may be sensitive.
            log.debug("Calling {}({})", functionName, argumentShapes(metadata.parameters()));
        }

        long startNanos = System.nanoTime();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            // Bound the call so a hung function cannot pin the thread/connection; stream
            // large SETOF reads in batches. Zero leaves the driver default untouched.
            if (config.queryTimeoutSeconds() > 0) {
                ps.setQueryTimeout(config.queryTimeoutSeconds());
            }
            if (config.fetchSize() > 0) {
                ps.setFetchSize(config.fetchSize());
            }
            bindParameters(ps, metadata.parameters(), args, mapperRegistry);

            Object result;
            if (metadata.returnTypeHandler() instanceof ReturnTypeHandler.VoidHandler) {
                ps.execute();
                result = null;
            } else {
                try (ResultSet rs = ps.executeQuery()) {
                    result = metadata.returnTypeHandler().extractResult(rs, mapperRegistry);
                }
            }
            logCompletion(functionName, startNanos);
            return result;
        } catch (SQLException e) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.warn("Function {} failed after {} ms (SQLSTATE={}): {}",
                    functionName, elapsedMs, e.getSQLState(), e.getMessage());
            throw new FunctionCallException(
                    "Failed to call function: " + functionName, e);
        }
    }

    private static void logCompletion(String functionName, long startNanos) {
        if (SLOW_CALL_THRESHOLD_MS <= 0 && !log.isDebugEnabled()) {
            return;
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (SLOW_CALL_THRESHOLD_MS > 0 && elapsedMs >= SLOW_CALL_THRESHOLD_MS) {
            log.warn("Function {} was slow: {} ms (threshold {} ms)",
                    functionName, elapsedMs, SLOW_CALL_THRESHOLD_MS);
        } else if (log.isDebugEnabled()) {
            log.debug("Function {} completed in {} ms", functionName, elapsedMs);
        }
    }

    /** Renders parameter types/cardinality (not values) for debug logging. */
    private static String argumentShapes(List<ParameterInfo> parameters) {
        StringJoiner joiner = new StringJoiner(", ");
        for (ParameterInfo p : parameters) {
            String shape = p.type().getSimpleName();
            if (p.isOptional()) {
                shape = "Optional<" + shape + ">";
            }
            if (p.isDomainObject()) {
                shape += " jsonb";
            }
            joiner.add(shape);
        }
        return joiner.toString();
    }

    /**
     * Builds: {@code SELECT * FROM function_name(?, ?, ...)}
     */
    private static String buildSql(String functionName, int paramCount) {
        var sb = new StringBuilder("SELECT * FROM ");
        sb.append(functionName).append('(');
        for (int i = 0; i < paramCount; i++) {
            if (i > 0) sb.append(", ");
            sb.append('?');
        }
        sb.append(')');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void bindParameters(
            PreparedStatement ps,
            List<ParameterInfo> paramInfos,
            Object[] args,
            MapperRegistry mapperRegistry) throws SQLException {

        for (int i = 0; i < paramInfos.size(); i++) {
            ParameterInfo info = paramInfos.get(i);
            Object arg = args[i];

            // A top-level Optional<E> parameter is unwrapped and bound here as E
            // (empty -> null); an Optional nested inside a domain object is instead
            // handled by Jackson's Jdk8Module when that object is serialized to JSONB.
            if (info.isOptional() && arg instanceof Optional<?> opt) {
                arg = opt.orElse(null);
            }

            if (arg == null) {
                ps.setNull(i + 1, Types.NULL);
            } else if (info.isDomainObject()) {
                DomainMapper<Object> mapper =
                        (DomainMapper<Object>) mapperRegistry.resolve(info.type());
                String json = mapper.toJson(arg);
                var pgObj = new PGobject();
                pgObj.setType("jsonb");
                pgObj.setValue(json);
                ps.setObject(i + 1, pgObj);
            } else if (arg instanceof Enum<?> enumVal) {
                ps.setString(i + 1, enumVal.name());
            } else if (arg instanceof Instant instant) {
                // pgjdbc's setObject cannot infer a SQL type for Instant; bind it as
                // the equivalent UTC OffsetDateTime (-> timestamptz), which it supports.
                ps.setObject(i + 1, instant.atOffset(ZoneOffset.UTC));
            } else if (arg instanceof ZonedDateTime zdt) {
                // Same limitation; the offset preserves the instant when stored as timestamptz.
                ps.setObject(i + 1, zdt.toOffsetDateTime());
            } else {
                ps.setObject(i + 1, arg);
            }
        }
    }
}
