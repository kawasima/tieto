package net.unit8.tieto.spring;

import net.unit8.tieto.core.TietoClient;
import net.unit8.tieto.core.annotation.TietoRepository;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;

/**
 * Auto-configuration for tieto Spring integration.
 *
 * <p>Wraps the application {@link DataSource} in a
 * {@link TransactionAwareDataSourceProxy} so that the Connection tieto acquires
 * per call participates in the surrounding {@code @Transactional} boundary and
 * is released (not physically closed) while a transaction is active.</p>
 *
 * <p>Also enables property-driven repository registration: setting
 * {@code tieto.base-packages} registers {@link TietoRepository}-annotated
 * interfaces without an {@link EnableTietoRepositories} annotation.</p>
 */
@AutoConfiguration
@ConditionalOnClass(TietoClient.class)
public class TietoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TietoClient tietoClient(DataSource dataSource) {
        return TietoClient.builder(new TransactionAwareDataSourceProxy(dataSource)).build();
    }

    /**
     * Registers repositories declared via {@code tieto.base-packages}. Static so it
     * runs early enough to contribute bean definitions before autowiring.
     */
    @Bean
    static BeanDefinitionRegistryPostProcessor tietoPropertyRepositoryRegistrar() {
        return new TietoPropertyRepositoryRegistrar();
    }
}
