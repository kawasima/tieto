package net.unit8.tieto.core.connection;

import net.unit8.tieto.core.StubDataSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link TietoDataSource#inTransaction} restores the Connection's
 * {@code autoCommit} to its prior value before returning it to the target, so a
 * pool that does not reset connection state cannot hand it back with
 * {@code autoCommit=false} and silently discard a later non-transactional write.
 */
class TietoDataSourceTest {

    /** A Connection proxy that records autoCommit transitions and the close lifecycle. */
    private static final class Recording implements InvocationHandler {
        boolean autoCommit;
        boolean closed;
        int commits;
        int rollbacks;
        Boolean autoCommitAtClose;   // autoCommit value captured at the moment close() ran

        Recording(boolean initialAutoCommit) {
            this.autoCommit = initialAutoCommit;
        }

        Connection proxy() {
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> {
                    autoCommit = (boolean) args[0];
                    yield null;
                }
                case "commit" -> {
                    commits++;
                    yield null;
                }
                case "rollback" -> {
                    rollbacks++;
                    yield null;
                }
                case "close" -> {
                    closed = true;
                    autoCommitAtClose = autoCommit;
                    yield null;
                }
                case "isClosed" -> closed;
                default -> null;
            };
        }
    }

    private static TietoDataSource dataSourceReturning(Connection conn) {
        return new TietoDataSource(new StubDataSource() {
            @Override
            public Connection getConnection() {
                return conn;
            }
        });
    }

    @Test
    void restoresAutoCommitToTrueAfterACommittedTransaction() throws Exception {
        Recording rec = new Recording(true);
        TietoDataSource dataSource = dataSourceReturning(rec.proxy());

        dataSource.inTransaction(() -> null);

        assertThat(rec.commits).isEqualTo(1);
        assertThat(rec.rollbacks).isZero();
        assertThat(rec.closed).isTrue();
        assertThat(rec.autoCommit).as("restored to prior value").isTrue();
        assertThat(rec.autoCommitAtClose).as("restored before close").isTrue();
    }

    @Test
    void restoresAutoCommitToTrueAfterARolledBackTransaction() {
        Recording rec = new Recording(true);
        TietoDataSource dataSource = dataSourceReturning(rec.proxy());

        assertThatThrownBy(() -> dataSource.inTransaction(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(rec.rollbacks).isEqualTo(1);
        assertThat(rec.commits).isZero();
        assertThat(rec.autoCommit).isTrue();
        assertThat(rec.autoCommitAtClose).as("restored before close").isTrue();
    }

    @Test
    void restoresThePriorValueWhenItWasNotTrue() throws Exception {
        // A pool may hand out a Connection that is already autoCommit=false; the
        // prior value must be restored, not hardcoded to true.
        Recording rec = new Recording(false);
        TietoDataSource dataSource = dataSourceReturning(rec.proxy());

        dataSource.inTransaction(() -> null);

        assertThat(rec.autoCommitAtClose).isFalse();
        assertThat(rec.autoCommit).isFalse();
    }
}
