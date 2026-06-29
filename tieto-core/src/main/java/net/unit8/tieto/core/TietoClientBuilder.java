package net.unit8.tieto.core;

import net.unit8.tieto.core.function.DefaultFunctionNameResolver;
import net.unit8.tieto.core.function.FunctionNameResolver;
import net.unit8.tieto.core.mapper.DomainMapper;
import net.unit8.tieto.core.mapper.MapperRegistry;

import javax.sql.DataSource;
import java.util.Objects;

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
        Objects.requireNonNull(domainClass, "domainClass must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        mapperRegistryBuilder.register(domainClass, mapper);
        return this;
    }

    /**
     * Overrides the default function naming strategy.
     */
    public TietoClientBuilder functionNameResolver(FunctionNameResolver resolver) {
        this.functionNameResolver = Objects.requireNonNull(resolver, "resolver must not be null");
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
