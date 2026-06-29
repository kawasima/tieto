package net.unit8.tieto.spring;

import net.unit8.tieto.core.TietoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Warns at startup when a {@link TietoClient} bean is built over a DataSource that is
 * not transaction-aware.
 *
 * <p>The auto-configured client wraps the application DataSource in a
 * {@link TransactionAwareDataSourceProxy} so tieto's per-call connection joins the
 * surrounding {@code @Transactional} boundary. A user who instead defines their own
 * {@code TietoClient} bean (the obvious way to add mappers) gets a client over the raw
 * DataSource and silently loses transaction participation. This guard surfaces that
 * mistake with a prominent log warning, pointing at {@link TietoClientCustomizer} as the
 * supported extension point.</p>
 */
final class TietoClientTransactionAwarenessGuard implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(TietoClientTransactionAwarenessGuard.class);

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof TietoClient client && !isTransactionAware(client.dataSource())) {
            log.warn("TietoClient bean '{}' is built over a DataSource that is not transaction-aware ({}). "
                            + "@Transactional participation will be silently lost: tieto acquires its own "
                            + "connection per call instead of joining the current Spring transaction. "
                            + "Add mappers or a custom name resolver via a TietoClientCustomizer bean instead "
                            + "of defining your own TietoClient, or wrap your DataSource in Spring's "
                            + "TransactionAwareDataSourceProxy.",
                    beanName, client.dataSource().getClass().getName());
        }
        return bean;
    }

    /**
     * Whether connections obtained from {@code dataSource} are bound to the current Spring
     * transaction — i.e. a {@link TransactionAwareDataSourceProxy} sits anywhere in the
     * delegation chain that {@code getConnection()} follows. The {@link DelegatingDataSource}
     * walk covers the standard Spring wrapping; as a fallback, a custom wrapper that is not a
     * {@code DelegatingDataSource} but exposes the proxy through the JDBC {@code unwrap}
     * contract is honoured too, so it is not falsely warned about.
     */
    static boolean isTransactionAware(DataSource dataSource) {
        DataSource current = dataSource;
        while (current != null) {
            if (current instanceof TransactionAwareDataSourceProxy) {
                return true;
            }
            if (current instanceof DelegatingDataSource delegating) {
                current = delegating.getTargetDataSource();
            } else {
                break;
            }
        }
        try {
            return dataSource.isWrapperFor(TransactionAwareDataSourceProxy.class);
        } catch (SQLException e) {
            return false;
        }
    }
}
