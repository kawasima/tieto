package net.unit8.tieto.core.proxy;

import net.unit8.tieto.core.connection.ConnectionProvider;
import net.unit8.tieto.core.function.FunctionInvoker;
import net.unit8.tieto.core.function.FunctionNameResolver;
import net.unit8.tieto.core.mapper.MapperRegistry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link InvocationHandler} that translates Repository method calls into
 * PostgreSQL function invocations.
 */
public final class RepositoryInvocationHandler implements InvocationHandler {

    private final Class<?> repositoryInterface;
    private final ConnectionProvider connectionProvider;
    private final MapperRegistry mapperRegistry;
    private final FunctionNameResolver nameResolver;
    private final ConcurrentMap<Method, MethodMetadata> metadataCache = new ConcurrentHashMap<>();

    public RepositoryInvocationHandler(
            Class<?> repositoryInterface,
            ConnectionProvider connectionProvider,
            MapperRegistry mapperRegistry,
            FunctionNameResolver nameResolver) {
        this.repositoryInterface = repositoryInterface;
        this.connectionProvider = connectionProvider;
        this.mapperRegistry = mapperRegistry;
        this.nameResolver = nameResolver;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Handle Object methods (toString, hashCode, equals)
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> repositoryInterface.getSimpleName() + "@tieto-proxy";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> method.invoke(this, args);
            };
        }

        // Handle default methods on the interface
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }

        MethodMetadata metadata = metadataCache.computeIfAbsent(
                method, m -> MethodMetadata.analyze(repositoryInterface, m));

        String functionName = nameResolver.resolve(repositoryInterface, method);

        return FunctionInvoker.invoke(
                connectionProvider,
                functionName,
                metadata,
                args,
                mapperRegistry
        );
    }
}
