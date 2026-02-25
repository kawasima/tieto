package net.unit8.tieto.core.function;

import net.unit8.tieto.core.connection.ConnectionProvider;
import net.unit8.tieto.core.exception.FunctionCallException;
import net.unit8.tieto.core.mapper.DomainMapper;
import net.unit8.tieto.core.mapper.MapperRegistry;
import net.unit8.tieto.core.proxy.MethodMetadata;
import net.unit8.tieto.core.proxy.ParameterInfo;
import net.unit8.tieto.core.proxy.ReturnTypeHandler;
import org.postgresql.util.PGobject;

import java.sql.*;
import java.util.List;

/**
 * Executes PostgreSQL functions via JDBC.
 *
 * <p>Uses {@code SELECT * FROM function_name(?, ...)} syntax rather than
 * CallableStatement, which works naturally with SETOF return types and JSONB.</p>
 */
public final class FunctionInvoker {

    private FunctionInvoker() {}

    /**
     * Invokes a PostgreSQL function and processes the result.
     */
    public static Object invoke(
            ConnectionProvider connectionProvider,
            String functionName,
            MethodMetadata metadata,
            Object[] args,
            MapperRegistry mapperRegistry) {

        String sql = buildSql(functionName, metadata.parameters().size());

        try {
            Connection conn = connectionProvider.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bindParameters(ps, metadata.parameters(), args, mapperRegistry);

                if (metadata.returnTypeHandler() instanceof ReturnTypeHandler.VoidHandler) {
                    ps.execute();
                    return null;
                }

                try (ResultSet rs = ps.executeQuery()) {
                    return metadata.returnTypeHandler().extractResult(rs, mapperRegistry);
                }
            }
        } catch (SQLException e) {
            throw new FunctionCallException(
                    "Failed to call function: " + functionName, e);
        }
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
            } else {
                ps.setObject(i + 1, arg);
            }
        }
    }
}
