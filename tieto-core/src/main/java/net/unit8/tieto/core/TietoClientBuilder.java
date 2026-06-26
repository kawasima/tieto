package net.unit8.tieto.core;

import net.unit8.tieto.core.function.DefaultFunctionNameResolver;
import net.unit8.tieto.core.function.FunctionNameResolver;
import net.unit8.tieto.core.mapper.DomainMapper;
import net.unit8.tieto.core.mapper.MapperRegistry;

import javax.sql.DataSource;

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

    private final DataSource dataSource;
    private final MapperRegistry.Builder mapperRegistryBuilder = MapperRegistry.builder();
    private FunctionNameResolver functionNameResolver = new DefaultFunctionNameResolver();

    TietoClientBuilder(DataSource dataSource) {
        this.dataSource = dataSource;
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
                dataSource,
                mapperRegistryBuilder.build(),
                functionNameResolver
        );
    }
}
