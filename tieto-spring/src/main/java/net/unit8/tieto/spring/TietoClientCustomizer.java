package net.unit8.tieto.spring;

import net.unit8.tieto.core.TietoClientBuilder;

/**
 * Callback for customizing the auto-configured {@link net.unit8.tieto.core.TietoClient}
 * (e.g. registering explicit {@link net.unit8.tieto.core.mapper.DomainMapper}s or a
 * custom function-name resolver) <em>without</em> replacing the bean.
 *
 * <p>This is the supported way to add mappers under Spring. Defining your own
 * {@code TietoClient} bean instead would bypass the framework's
 * {@code TransactionAwareDataSourceProxy} wrapping and silently break
 * {@code @Transactional} participation; registering a {@code TietoClientCustomizer}
 * keeps that wrapping intact.</p>
 *
 * <p>All {@code TietoClientCustomizer} beans are applied in order (honouring
 * {@link org.springframework.core.annotation.Order}).</p>
 *
 * <pre>{@code
 * @Bean
 * TietoClientCustomizer specialOrderMapper() {
 *     return builder -> builder.mapper(SpecialOrder.class, new SpecialOrderMapper());
 * }
 * }</pre>
 */
@FunctionalInterface
public interface TietoClientCustomizer {

    /**
     * Customizes the builder used to create the auto-configured {@code TietoClient}.
     * The builder is already backed by the transaction-aware DataSource.
     */
    void customize(TietoClientBuilder builder);
}
