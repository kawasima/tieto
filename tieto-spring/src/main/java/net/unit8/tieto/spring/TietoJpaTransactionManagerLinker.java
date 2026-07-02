package net.unit8.tieto.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;
import java.lang.reflect.Method;

/**
 * Makes {@code @Transactional} participation actually work under Spring Data JPA by linking a
 * {@code JpaTransactionManager} that has no {@code dataSource} of its own to the single
 * application {@link DataSource}.
 *
 * <p>Boot's auto-configured {@code JpaTransactionManager} leaves its {@code dataSource} unset, so
 * it never binds a JDBC {@code ConnectionHolder} to the DataSource. tieto's per-call connection
 * (obtained through a {@code TransactionAwareDataSourceProxy}) then comes fresh from the pool,
 * outside the JPA transaction. Setting the manager's {@code dataSource} to the one the
 * {@code EntityManagerFactory} uses makes JPA expose its JDBC connection through
 * {@code DataSourceUtils}, so tieto — and any {@code JdbcTemplate} — share the transaction's
 * connection. This is the standard Spring recipe for mixing JDBC access with JPA.</p>
 *
 * <p>Conservative by construction: it acts only on a {@code JpaTransactionManager} whose
 * {@code dataSource} is {@code null} (an explicitly configured one is left alone) and only when
 * exactly one {@code DataSource} bean exists (so it never guesses in a multi-DataSource setup).
 * {@code JpaTransactionManager} is reached reflectively, so tieto-spring keeps no {@code spring-orm}
 * dependency. Disable with {@code tieto.link-jpa-transaction-manager=false}.</p>
 */
final class TietoJpaTransactionManagerLinker implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(TietoJpaTransactionManagerLinker.class);

    private static final String JPA_TRANSACTION_MANAGER =
            "org.springframework.orm.jpa.JpaTransactionManager";

    private final ObjectProvider<DataSource> dataSources;

    TietoJpaTransactionManagerLinker(ObjectProvider<DataSource> dataSources) {
        this.dataSources = dataSources;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (!isJpaTransactionManager(bean.getClass())) {
            return bean;
        }
        try {
            Method getDataSource = bean.getClass().getMethod("getDataSource");
            if (getDataSource.invoke(bean) != null) {
                return bean;   // an explicitly configured dataSource is respected
            }
            DataSource dataSource = dataSources.getIfUnique();
            if (dataSource == null) {
                return bean;   // zero or several DataSources: do not guess which one JPA uses
            }
            bean.getClass().getMethod("setDataSource", DataSource.class).invoke(bean, dataSource);
            log.info("Linked JpaTransactionManager '{}' to the application DataSource so tieto "
                    + "(and any JdbcTemplate) share the JPA transaction's connection. Disable with "
                    + "tieto.link-jpa-transaction-manager=false.", beanName);
        } catch (ReflectiveOperationException e) {
            // Not a JpaTransactionManager shape we can link (no get/setDataSource); leave it alone.
        }
        return bean;
    }

    private static boolean isJpaTransactionManager(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals(JPA_TRANSACTION_MANAGER)) {
                return true;
            }
        }
        return false;
    }
}
