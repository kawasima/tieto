package net.unit8.tieto.core.proxy;

import net.unit8.tieto.core.function.FunctionInvoker;
import net.unit8.tieto.core.function.FunctionNameResolver;
import net.unit8.tieto.core.mapper.MapperRegistry;

import javax.sql.DataSource;
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
    private final DataSource dataSource;
    private final MapperRegistry mapperRegistry;
    private final FunctionNameResolver nameResolver;
    private final ConcurrentMap<Method, MethodMetadata> metadataCache = new ConcurrentHashMap<>();

    public RepositoryInvocationHandler(
            Class<?> repositoryInterface,
            DataSource dataSource,
            MapperRegistry mapperRegistry,
            FunctionNameResolver nameResolver) {
        this.repositoryInterface = repositoryInterface;
        this.dataSource = dataSource;
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
                // A JDK Proxy only dispatches public, non-final Object methods,
                // i.e. exactly the three above; nothing else can reach here.
                default -> throw new UnsupportedOperationException(
                        "Unexpected Object method on tieto proxy: " + method.getName());
            };
        }

        // Handle default methods on the interface
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }

        MethodMetadata metadata = metadataCache.computeIfAbsent(
                method, m -> MethodMetadata.analyze(repositoryInterface, m, nameResolver));

        return FunctionInvoker.invoke(
                dataSource,
                metadata.functionName(),
                metadata,
                args,
                mapperRegistry
        );
    }
}
