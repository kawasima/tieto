package net.unit8.tieto.spring;

import net.unit8.tieto.core.TietoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.ResourceTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * Warns at startup when a {@link TietoClient} is transaction-aware in <em>structure</em>
 * — its DataSource is wrapped in a {@link TransactionAwareDataSourceProxy} — but no
 * configured transaction manager actually binds a JDBC connection to the DataSource tieto
 * uses, so {@code @Transactional} participation is silently lost at runtime.
 *
 * <p>The classic case is Spring Data JPA. Boot's auto-configured {@code JpaTransactionManager}
 * does not set its {@code dataSource}, so it never binds a {@code ConnectionHolder} to the
 * DataSource. Inside a {@code @Transactional} method the {@code TransactionAwareDataSourceProxy}
 * then finds nothing bound and hands tieto a fresh pool connection in its own autocommit —
 * tieto's writes are not rolled back with the JPA transaction and its reads do not see the
 * transaction's uncommitted state.</p>
 *
 * <p>{@link TietoClientTransactionAwarenessGuard} cannot detect this: the proxy <em>is</em>
 * present, which is necessary but not sufficient. This guard closes that gap by checking, at
 * startup, that some transaction manager binds to the DataSource tieto wraps.</p>
 */
final class TietoTransactionParticipationGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(TietoTransactionParticipationGuard.class);

    private final ObjectProvider<TietoClient> clients;
    private final ObjectProvider<PlatformTransactionManager> transactionManagers;

    TietoTransactionParticipationGuard(ObjectProvider<TietoClient> clients,
                                       ObjectProvider<PlatformTransactionManager> transactionManagers) {
        this.clients = clients;
        this.transactionManagers = transactionManagers;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<PlatformTransactionManager> managers = transactionManagers.orderedStream().toList();
        if (managers.isEmpty()) {
            // No Spring-managed transactions at all; @Transactional is not in play, so there is
            // no participation to lose. (Spring itself fails a @Transactional call with no manager.)
            return;
        }
        List<DataSource> boundDataSources = managers.stream()
                .map(TietoTransactionParticipationGuard::managedDataSource)
                .filter(Objects::nonNull)
                .map(TietoTransactionParticipationGuard::rootDataSource)
                .toList();

        clients.forEach(client -> {
            DataSource tietoTarget = transactionAwareTarget(client.dataSource());
            if (tietoTarget == null) {
                // Not TransactionAwareDataSourceProxy-wrapped; the structural guard already warns.
                return;
            }
            boolean participates = boundDataSources.stream().anyMatch(ds -> ds == tietoTarget);
            if (!participates) {
                log.warn("tieto is wrapped in a TransactionAwareDataSourceProxy, but none of the "
                        + "configured transaction managers binds a JDBC connection to the DataSource "
                        + "tieto uses. @Transactional participation will be silently lost: tieto acquires "
                        + "its own connection per call in its own autocommit, so its writes are not rolled "
                        + "back with the surrounding transaction and its reads do not see uncommitted "
                        + "state. This is the typical Spring Data JPA setup, where the auto-configured "
                        + "JpaTransactionManager does not expose its JDBC connection. Use a "
                        + "DataSourceTransactionManager over the same DataSource, or set that DataSource "
                        + "on your JpaTransactionManager, so tieto and JPA share the transaction's "
                        + "connection.");
            }
        });
    }

    /**
     * The DataSource a {@link TransactionAwareDataSourceProxy} delegates to, or {@code null} if the
     * given DataSource has no such proxy in its chain. Uses the same detection as
     * {@link TietoClientTransactionAwarenessGuard} (including its {@code isWrapperFor} fallback for
     * non-{@link DelegatingDataSource} wrappers), so the two guards never disagree about whether
     * tieto is transaction-aware.
     */
    private static DataSource transactionAwareTarget(DataSource dataSource) {
        TransactionAwareDataSourceProxy proxy =
                TietoClientTransactionAwarenessGuard.transactionAwareProxy(dataSource);
        return proxy == null ? null : rootDataSource(proxy.getTargetDataSource());
    }

    /** Unwraps {@link DelegatingDataSource} layers down to the underlying DataSource. */
    private static DataSource rootDataSource(DataSource dataSource) {
        DataSource current = dataSource;
        while (current instanceof DelegatingDataSource delegating && delegating.getTargetDataSource() != null) {
            current = delegating.getTargetDataSource();
        }
        return current;
    }

    /**
     * The DataSource a transaction manager binds connections to, or {@code null} if it binds none.
     * {@code DataSourceTransactionManager} exposes it as its resource factory. {@code
     * JpaTransactionManager} exposes {@code getDataSource()} but is not on tieto-spring's classpath,
     * so it is reached reflectively — a {@code null} return there is exactly the risky case where the
     * auto-configured manager never had its dataSource set.
     */
    private static DataSource managedDataSource(PlatformTransactionManager manager) {
        if (manager instanceof ResourceTransactionManager rtm
                && rtm.getResourceFactory() instanceof DataSource ds) {
            return ds;
        }
        try {
            Method getDataSource = manager.getClass().getMethod("getDataSource");
            if (getDataSource.invoke(manager) instanceof DataSource ds) {
                return ds;
            }
        } catch (ReflectiveOperationException ignored) {
            // No getDataSource(): a JTA or custom manager that binds no DataSource.
        }
        return null;
    }
}
