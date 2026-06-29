package net.unit8.tieto.spring;

import net.unit8.tieto.core.TietoClient;
import net.unit8.tieto.core.TietoClientBuilder;
import net.unit8.tieto.core.annotation.TietoRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 * <p>Ordered after {@code DataSourceAutoConfiguration} and guarded by
 * {@link ConditionalOnSingleCandidate}, so the {@code TietoClient} bean backs
 * off (rather than failing context refresh) when there is no {@link DataSource},
 * or several with no {@code @Primary}. This lets the dependency sit on the
 * classpath of a module that does not use tieto. Note that a module that
 * <em>does</em> wire tieto repositories (via {@code tieto.base-packages} or
 * {@link EnableTietoRepositories}) without a resolvable {@code DataSource} still
 * fails refresh — correctly, since those repositories cannot be created.</p>
 *
 * <p>To add explicit mappers or a custom function-name resolver, register a
 * {@link TietoClientCustomizer} bean rather than replacing the {@code TietoClient}
 * bean — the customizer keeps the transaction-aware DataSource wrapping intact.</p>
 *
 * <p>Also enables property-driven repository registration: setting
 * {@code tieto.base-packages} registers {@link TietoRepository}-annotated
 * interfaces without an {@link EnableTietoRepositories} annotation.</p>
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(TietoProperties.class)
public class TietoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnSingleCandidate(DataSource.class)
    public TietoClient tietoClient(DataSource dataSource,
                                   ObjectProvider<TietoClientCustomizer> customizers) {
        TietoClientBuilder builder =
                TietoClient.builder(new TransactionAwareDataSourceProxy(dataSource));
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder.build();
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
