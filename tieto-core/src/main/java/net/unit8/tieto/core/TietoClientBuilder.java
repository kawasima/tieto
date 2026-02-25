package net.unit8.tieto.core;

import net.unit8.tieto.core.connection.ConnectionProvider;
import net.unit8.tieto.core.function.DefaultFunctionNameResolver;
import net.unit8.tieto.core.function.FunctionNameResolver;
import net.unit8.tieto.core.mapper.DomainMapper;
import net.unit8.tieto.core.mapper.MapperRegistry;

/**
 * Builder for {@link TietoClient}.
 *
 * <pre>{@code
 * TietoClient client = TietoClient.builder(dataSource)
 *     .mapper(SpecialOrder.class, new SpecialOrderMapper())
 *     .build();
 * }</pre>
 */
public final class TietoClientBuilder {

    private final ConnectionProvider connectionProvider;
    private final MapperRegistry.Builder mapperRegistryBuilder = MapperRegistry.builder();
    private FunctionNameResolver functionNameResolver = new DefaultFunctionNameResolver();

    TietoClientBuilder(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    /**
     * Registers an explicit mapper for a domain class.
     * Only needed for classes that require special handling beyond
     * convention-based mapping.
     */
    public <T> TietoClientBuilder mapper(Class<T> domainClass, DomainMapper<T> mapper) {
        mapperRegistryBuilder.register(domainClass, mapper);
        return this;
    }

    /**
     * Overrides the default function naming strategy.
     */
    public TietoClientBuilder functionNameResolver(FunctionNameResolver resolver) {
        this.functionNameResolver = resolver;
        return this;
    }

    public TietoClient build() {
        return new TietoClient(
                connectionProvider,
                mapperRegistryBuilder.build(),
                functionNameResolver
        );
    }
}
