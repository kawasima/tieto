package net.unit8.tieto.core.function;

import net.unit8.tieto.core.StubDataSource;
import net.unit8.tieto.core.mapper.MapperRegistry;
import net.unit8.tieto.core.proxy.MethodMetadata;
import net.unit8.tieto.core.proxy.ReturnTypeHandler;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link FunctionInvoker} applies the {@link InvocationConfig}
 * query timeout and SETOF fetch size to the {@link PreparedStatement}.
 */
class FunctionInvokerConfigTest {

    /** Records the int passed to setQueryTimeout/setFetchSize on a stub statement. */
    private final Map<String, Integer> applied = new HashMap<>();

    private static Method anyMethod() throws NoSuchMethodException {
        return Object.class.getMethod("toString");
    }

    @Test
    void appliesQueryTimeoutToStatement() throws Exception {
        MethodMetadata metadata = new MethodMetadata(anyMethod(), "fn",
                new ReturnTypeHandler.VoidHandler(), List.of());

        FunctionInvoker.invoke(recordingDataSource(), "fn", metadata, new Object[]{},
                MapperRegistry.builder().build(), new InvocationConfig(15, 0));

        assertThat(applied).containsEntry("setQueryTimeout", 15);
    }

    @Test
    void appliesFetchSizeForSetofReads() throws Exception {
        MethodMetadata metadata = new MethodMetadata(anyMethod(), "fn",
                new ReturnTypeHandler.ListHandler(String.class), List.of());

        Object result = FunctionInvoker.invoke(recordingDataSource(), "fn", metadata, new Object[]{},
                MapperRegistry.builder().build(), new InvocationConfig(0, 500));

        assertThat(result).isEqualTo(List.of());
        assertThat(applied).containsEntry("setFetchSize", 500);
    }

    @Test
    void zeroValuesAreNotAppliedToStatement() throws Exception {
        MethodMetadata metadata = new MethodMetadata(anyMethod(), "fn",
                new ReturnTypeHandler.ListHandler(String.class), List.of());

        FunctionInvoker.invoke(recordingDataSource(), "fn", metadata, new Object[]{},
                MapperRegistry.builder().build(), new InvocationConfig(0, 0));

        assertThat(applied).doesNotContainKeys("setQueryTimeout", "setFetchSize");
    }

    private DataSource recordingDataSource() {
        ResultSet emptyRs = (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (p, m, a) -> "next".equals(m.getName()) ? Boolean.FALSE : defaultFor(m));
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "setQueryTimeout", "setFetchSize" -> {
                            applied.put(m.getName(), (Integer) a[0]);
                            return null;
                        }
                        case "executeQuery" -> { return emptyRs; }
                        case "execute" -> { return Boolean.FALSE; }
                        default -> { return defaultFor(m); }
                    }
                });
        Connection conn = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (p, m, a) -> "prepareStatement".equals(m.getName()) ? ps : defaultFor(m));
        return new StubDataSource() {
            @Override
            public Connection getConnection() {
                return conn;
            }
        };
    }

    /** Sane default return for unhandled proxy methods (void/close/etc.). */
    private static Object defaultFor(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return Boolean.FALSE;
        if (r == int.class) return 0;
        return null;
    }
}
