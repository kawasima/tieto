package net.unit8.tieto.core.connection;

import net.unit8.tieto.core.StubDataSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link TietoDataSource#inTransaction} returns the Connection to
 * the target in a safe state: autoCommit is restored to its prior value (so a
 * non-resetting pool cannot silently discard a later non-transactional write),
 * but only when doing so is safe — it never flips a genuinely autoCommit=false
 * connection, never commits in-doubt work after a failed rollback, and always
 * closes the Connection even if the restore fails.
 */
class TietoDataSourceTest {

    /** A Connection proxy that records the autoCommit lifecycle and can be told to fail. */
    private static final class Recording implements InvocationHandler {
        boolean autoCommit;
        boolean closed;
        int commits;
        int rollbacks;
        Boolean autoCommitAtClose;
        final List<Boolean> setAutoCommitValues = new ArrayList<>();

        boolean throwOnGetAutoCommit;
        boolean throwOnCommit;
        boolean throwOnRollback;
        boolean throwUncheckedOnSetAutoCommitTrue;

        Recording(boolean initialAutoCommit) {
            this.autoCommit = initialAutoCommit;
        }

        Connection proxy() {
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
            switch (method.getName()) {
                case "getAutoCommit":
                    if (throwOnGetAutoCommit) {
                        throw new SQLException("getAutoCommit failed");
                    }
                    return autoCommit;
                case "setAutoCommit":
                    boolean value = (boolean) args[0];
                    setAutoCommitValues.add(value);
                    if (value && throwUncheckedOnSetAutoCommitTrue) {
                        throw new IllegalStateException("connection closed by pool");
                    }
                    autoCommit = value;
                    return null;
                case "commit":
                    commits++;
                    if (throwOnCommit) {
                        throw new SQLException("commit failed");
                    }
                    return null;
                case "rollback":
                    rollbacks++;
                    if (throwOnRollback) {
                        throw new SQLException("rollback failed");
                    }
                    return null;
                case "close":
                    closed = true;
                    autoCommitAtClose = autoCommit;
                    return null;
                case "isClosed":
                    return closed;
                case "toString":
                    return "Recording";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException("unexpected call: " + method.getName());
            }
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
    void leavesAnAlreadyAutoCommitFalseConnectionUntouched() throws Exception {
        // A pool may hand out a Connection already at autoCommit=false; we must not
        // flip it to true, and need not change it at all.
        Recording rec = new Recording(false);
        TietoDataSource dataSource = dataSourceReturning(rec.proxy());

        dataSource.inTransaction(() -> null);

        assertThat(rec.setAutoCommitValues).as("never touched autoCommit").isEmpty();
        assertThat(rec.autoCommit).isFalse();
        assertThat(rec.closed).isTrue();
    }

    @Test
    void doesNotForceCommitWhenRollbackFails() {
        // commit fails, then rollback fails: there may be in-doubt work, so autoCommit
        // must NOT be flipped to true (that would commit it). close() handles the rest.
        Recording rec = new Recording(true);
        rec.throwOnCommit = true;
        rec.throwOnRollback = true;
        TietoDataSource dataSource = dataSourceReturning(rec.proxy());

        assertThatThrownBy(() -> dataSource.inTransaction(() -> null))
                .isInstanceOf(SQLException.class)
                .hasMessage("commit failed");

        assertThat(rec.setAutoCommitValues).as("only the initial setAutoCommit(false), no restore-to-true")
                .containsExactly(false);
        assertThat(rec.closed).as("connection still closed/returned").isTrue();
    }

    @Test
    void stillClosesWhenRestoringAutoCommitThrowsUnchecked() throws Exception {
        // A pool proxy may throw an unchecked exception from setAutoCommit; that must not
        // prevent the Connection from being closed/returned.
        Recording rec = new Recording(true);
        rec.throwUncheckedOnSetAutoCommitTrue = true;
        TietoDataSource dataSource = dataSourceReturning(rec.proxy());

        dataSource.inTransaction(() -> null);   // restore throws, but is swallowed

        assertThat(rec.commits).isEqualTo(1);
        assertThat(rec.closed).as("closed despite the restore failure").isTrue();
    }

    @Test
    void doesNotFlipTheConnectionWhenGetAutoCommitThrows() {
        // If getAutoCommit() throws, we never changed autoCommit, so we must leave it alone.
        Recording rec = new Recording(false);
        rec.throwOnGetAutoCommit = true;
        TietoDataSource dataSource = dataSourceReturning(rec.proxy());

        assertThatThrownBy(() -> dataSource.inTransaction(() -> null))
                .isInstanceOf(SQLException.class);

        assertThat(rec.setAutoCommitValues).as("connection left untouched").isEmpty();
        assertThat(rec.closed).isTrue();
    }

    @Test
    void nonTransactionalConnectionOnAnAutoCommitTruePoolIsReturnedUnwrapped() throws Exception {
        // The common case: the pool hands out an autoCommit=true connection. tieto must not
        // touch its autoCommit and must physically close it on close() (non-transactional path).
        Recording rec = new Recording(true);
        Connection pooled = rec.proxy();
        TietoDataSource dataSource = dataSourceReturning(pooled);

        try (Connection conn = dataSource.getConnection()) {
            assertThat(conn).as("returned unwrapped").isSameAs(pooled);
        }

        assertThat(rec.setAutoCommitValues).as("autoCommit left untouched").isEmpty();
        assertThat(rec.closed).as("physically closed on the non-transactional path").isTrue();
    }

    @Test
    void nonTransactionalConnectionOnAnAutoCommitFalsePoolCommitsAndRestoresOnClose() throws Exception {
        // A pool whose baseline is autoCommit=false: the borrowed connection is switched to
        // autoCommit=true for the call so the write is durable, then restored to false and
        // physically closed on close() so the pool's baseline survives.
        Recording rec = new Recording(false);
        TietoDataSource dataSource = dataSourceReturning(rec.proxy());

        Connection conn = dataSource.getConnection();
        assertThat(rec.autoCommit).as("switched to autoCommit=true for the call").isTrue();

        conn.close();

        assertThat(rec.setAutoCommitValues).as("true for the call, then restored to false")
                .containsExactly(true, false);
        assertThat(rec.autoCommitAtClose).as("pool baseline restored before close").isFalse();
        assertThat(rec.closed).as("physically closed").isTrue();
    }

    @Test
    void aNestedTransactionJoinsWithoutOpeningOrCommittingASecondTime() throws Exception {
        Recording rec = new Recording(true);
        TietoDataSource dataSource = dataSourceReturning(rec.proxy());

        boolean[] innerRan = {false};
        dataSource.inTransaction(() -> {
            try {
                dataSource.inTransaction(() -> {
                    innerRan[0] = true;   // joins the enclosing transaction
                    return null;
                });
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return null;
        });

        assertThat(innerRan[0]).isTrue();
        assertThat(rec.commits).as("only the outer transaction commits").isEqualTo(1);
        assertThat(rec.closed).isTrue();
    }
}
