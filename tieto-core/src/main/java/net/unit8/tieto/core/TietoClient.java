package net.unit8.tieto.core;

import net.unit8.tieto.core.connection.ConnectionProvider;
import net.unit8.tieto.core.connection.DataSourceConnectionProvider;
import net.unit8.tieto.core.function.FunctionNameResolver;
import net.unit8.tieto.core.mapper.MapperRegistry;
import net.unit8.tieto.core.proxy.RepositoryInvocationHandler;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;

/**
 * Main entry point for tieto. Creates dynamic proxy implementations of
 * Repository interfaces that delegate to PostgreSQL functions.
 *
 * <pre>{@code
 * TietoClient tieto = TietoClient.builder(dataSource).build();
 * OrderRepository repo = tieto.createRepository(OrderRepository.class);
 * Optional<Order> order = repo.findById(1L);
 * }</pre>
 */
public final class TietoClient {

    private final ConnectionProvider connectionProvider;
    private final MapperRegistry mapperRegistry;
    private final FunctionNameResolver functionNameResolver;

    TietoClient(ConnectionProvider connectionProvider,
                MapperRegistry mapperRegistry,
                FunctionNameResolver functionNameResolver) {
        this.connectionProvider = connectionProvider;
        this.mapperRegistry = mapperRegistry;
        this.functionNameResolver = functionNameResolver;
    }

    /**
     * Creates a builder backed by the given DataSource.
     */
    public static TietoClientBuilder builder(DataSource dataSource) {
        return new TietoClientBuilder(new DataSourceConnectionProvider(dataSource));
    }

    /**
     * Creates a builder backed by a custom ConnectionProvider.
     * Useful for Spring integration or other frameworks.
     */
    public static TietoClientBuilder builder(ConnectionProvider connectionProvider) {
        return new TietoClientBuilder(connectionProvider);
    }

    /**
     * Creates a proxy implementation of the given Repository interface.
     *
     * @param repositoryInterface the Repository interface class
     * @param <T> the Repository type
     * @return a proxy that delegates to PostgreSQL functions
     * @throws IllegalArgumentException if the argument is not an interface
     */
    @SuppressWarnings("unchecked")
    public <T> T createRepository(Class<T> repositoryInterface) {
        if (!repositoryInterface.isInterface()) {
            throw new IllegalArgumentException(
                    repositoryInterface.getName() + " is not an interface");
        }
        return (T) Proxy.newProxyInstance(
                repositoryInterface.getClassLoader(),
                new Class<?>[]{repositoryInterface},
                new RepositoryInvocationHandler(
                        repositoryInterface,
                        connectionProvider,
                        mapperRegistry,
                        functionNameResolver
                )
        );
    }
}
