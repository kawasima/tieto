package net.unit8.tieto.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.unit8.tieto.core.connection.TietoDataSource;
import net.unit8.tieto.core.exception.FunctionCallException;
import net.unit8.tieto.core.function.FunctionInvoker;
import net.unit8.tieto.core.mapper.MapperRegistry;
import net.unit8.tieto.core.proxy.MethodMetadata;
import net.unit8.tieto.core.proxy.ParameterInfo;
import net.unit8.tieto.core.proxy.ReturnTypeHandler;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The runtime emits SLF4J logs for the function-invocation path and the
 * connection/transaction lifecycle. These tests capture the events via a logback
 * appender.
 */
class RuntimeLoggingTest {

    /** Attaches a list appender to a class's logger at DEBUG and returns it. */
    private static ListAppender<ILoggingEvent> capture(Class<?> loggerFor) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerFor);
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @Test
    void invocationFailureLogsAWarnWithFunctionNameAndSqlState() throws Exception {
        ListAppender<ILoggingEvent> log = capture(FunctionInvoker.class);

        Method anyMethod = Object.class.getMethod("toString");
        MethodMetadata metadata = new MethodMetadata(anyMethod, "order_repository_find_by_id_v1",
                new ReturnTypeHandler.VoidHandler(),
                List.of(new ParameterInfo(0, "id", Long.class, null, false, false)));
        DataSource failing = new StubDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("unique violation", "23505");
            }
        };

        assertThatThrownBy(() -> FunctionInvoker.invoke(
                failing, "order_repository_find_by_id_v1", metadata, new Object[]{1L},
                MapperRegistry.builder().build()))
                .isInstanceOf(FunctionCallException.class)
                .extracting(e -> ((FunctionCallException) e).getSqlState())
                .isEqualTo("23505");

        // The DEBUG call line records the argument shape (the type, not the value).
        assertThat(log.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage())
                    .contains("order_repository_find_by_id_v1(Long)");
        });
        // The failure is logged at WARN with the SQLSTATE.
        assertThat(log.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("order_repository_find_by_id_v1")
                    .contains("23505");
        });
    }

    @Test
    void transactionCommitIsLoggedAtDebug() throws Exception {
        ListAppender<ILoggingEvent> log = capture(TietoDataSource.class);

        Connection conn = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> "getAutoCommit".equals(method.getName()) ? Boolean.TRUE : null);
        DataSource target = new StubDataSource() {
            @Override
            public Connection getConnection() {
                return conn;
            }
        };

        new TietoDataSource(target).inTransaction(() -> null);

        assertThat(log.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).contains("Committed transaction");
        });
    }
}
