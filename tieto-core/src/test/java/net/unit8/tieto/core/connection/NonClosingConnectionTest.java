package net.unit8.tieto.core.connection;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@link NonClosingConnection#wrap}: {@code close()} is swallowed (the
 * surrounding transaction owns the physical close) while every other call
 * delegates to the target, and a target exception is propagated unwrapped.
 */
class NonClosingConnectionTest {

    private final List<String> targetCalls = new ArrayList<>();

    private Connection recordingTarget() {
        InvocationHandler handler = (proxy, method, args) -> {
            targetCalls.add(method.getName());
            return switch (method.getName()) {
                case "getCatalog" -> "mydb";
                case "isClosed" -> false;
                case "setAutoCommit" -> throw new SQLException("autocommit not allowed");
                default -> null;
            };
        };
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class}, handler);
    }

    @Test
    void closeIsANoOpAndNeverReachesTheTarget() throws SQLException {
        Connection wrapped = NonClosingConnection.wrap(recordingTarget());

        wrapped.close();

        assertThat(targetCalls).doesNotContain("close");
    }

    @Test
    void otherCallsDelegateToTheTarget() throws SQLException {
        Connection wrapped = NonClosingConnection.wrap(recordingTarget());

        assertThat(wrapped.getCatalog()).isEqualTo("mydb");
        assertThat(wrapped.isClosed()).isFalse();
        assertThat(targetCalls).contains("getCatalog", "isClosed");
    }

    @Test
    void propagatesTargetExceptionsUnwrapped() {
        Connection wrapped = NonClosingConnection.wrap(recordingTarget());

        // Not wrapped in InvocationTargetException/UndeclaredThrowableException.
        assertThatThrownBy(() -> wrapped.setAutoCommit(false))
                .isInstanceOf(SQLException.class)
                .hasMessage("autocommit not allowed");
    }
}
