package net.unit8.tieto.core;

import net.unit8.tieto.core.function.DefaultFunctionNameResolver;
import net.unit8.tieto.core.function.FunctionNameResolver;
import net.unit8.tieto.core.function.InvocationConfig;
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
    private InvocationConfig invocationConfig = InvocationConfig.fromSystemProperties();

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

    /**
     * Sets the per-statement query timeout in seconds (default
     * {@value InvocationConfig#DEFAULT_QUERY_TIMEOUT_SECONDS}, overridable via the
     * {@code tieto.query-timeout-seconds} system property). {@code 0} disables the timeout.
     *
     * @see InvocationConfig
     */
    public TietoClientBuilder queryTimeoutSeconds(int seconds) {
        this.invocationConfig = new InvocationConfig(seconds, invocationConfig.fetchSize());
        return this;
    }

    /**
     * Sets the JDBC fetch size for {@code SETOF} reads (default 0 = driver default,
     * overridable via the {@code tieto.fetch-size} system property). Takes effect on the
     * transactional path, where pgjdbc fetches by cursor.
     *
     * @see InvocationConfig
     */
    public TietoClientBuilder fetchSize(int fetchSize) {
        this.invocationConfig = new InvocationConfig(invocationConfig.queryTimeoutSeconds(), fetchSize);
        return this;
    }

    public TietoClient build() {
        return new TietoClient(
                dataSource,
                mapperRegistryBuilder.build(),
                functionNameResolver,
                invocationConfig
        );
    }
}
