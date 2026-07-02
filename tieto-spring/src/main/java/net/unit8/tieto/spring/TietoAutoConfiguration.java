package net.unit8.tieto.spring;

import net.unit8.tieto.core.TietoClient;
import net.unit8.tieto.core.TietoClientBuilder;
import net.unit8.tieto.core.annotation.TietoRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;

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
// Ordered after DataSource auto-configuration by name rather than by class, so tieto-spring
// needs no dependency on the module that owns it. The baseline is Spring Boot 4, where the class
// lives in spring-boot-jdbc; the second name is the Boot 3.x home, kept only so the ordering still
// applies if this happens to run on an older Boot — not a supported target. An absent name is ignored.
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",   // Boot 4 (baseline)
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"    // Boot 3.x (defensive)
})
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

    /**
     * Warns when any {@code TietoClient} bean — including a user-supplied one that backs
     * off the auto-configured client — is not built over a transaction-aware DataSource,
     * so the silent loss of {@code @Transactional} participation is surfaced. Static so it
     * is registered as infrastructure without forcing early initialization of other beans.
     */
    @Bean
    static TietoClientTransactionAwarenessGuard tietoClientTransactionAwarenessGuard() {
        return new TietoClientTransactionAwarenessGuard();
    }

    /**
     * Warns when tieto is structurally transaction-aware (wrapped in a
     * {@link TransactionAwareDataSourceProxy}) but no configured transaction manager binds
     * to the DataSource tieto uses, so {@code @Transactional} participation is lost at runtime
     * — the typical Spring Data JPA setup. Complements the structural
     * {@link TietoClientTransactionAwarenessGuard}, which only sees the proxy's presence.
     */
    @Bean
    TietoTransactionParticipationGuard tietoTransactionParticipationGuard(
            ObjectProvider<TietoClient> clients,
            ObjectProvider<PlatformTransactionManager> transactionManagers) {
        return new TietoTransactionParticipationGuard(clients, transactionManagers);
    }

    /**
     * Links a {@code JpaTransactionManager} with no {@code dataSource} of its own to the single
     * application DataSource, so tieto's calls actually join the JPA {@code @Transactional}
     * boundary rather than escaping onto a fresh pool connection. Static, since a
     * {@link org.springframework.beans.factory.config.BeanPostProcessor} must be registered as
     * infrastructure. Disable with {@code tieto.link-jpa-transaction-manager=false}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "tieto", name = "link-jpa-transaction-manager",
            havingValue = "true", matchIfMissing = true)
    static TietoJpaTransactionManagerLinker tietoJpaTransactionManagerLinker(
            ObjectProvider<DataSource> dataSources) {
        return new TietoJpaTransactionManagerLinker(dataSources);
    }
}
