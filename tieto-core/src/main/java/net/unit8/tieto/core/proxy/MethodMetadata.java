package net.unit8.tieto.core.proxy;

import net.unit8.tieto.core.function.FunctionNameResolver;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Pre-analyzed metadata for a Repository method.
 *
 * <p>Captures the resolved function name, return type handler and parameter
 * information so that none of them are re-analyzed on every invocation.</p>
 */
public record MethodMetadata(
        Method method,
        String functionName,
        ReturnTypeHandler returnTypeHandler,
        List<ParameterInfo> parameters
) {

    /**
     * Analyzes a method and creates its metadata, resolving the (immutable per
     * method) PostgreSQL function name once so the proxy need not recompute it.
     */
    public static MethodMetadata analyze(Class<?> repoInterface, Method method,
                                         FunctionNameResolver nameResolver) {
        String functionName = nameResolver.resolve(repoInterface, method);
        ReturnTypeHandler handler = ReturnTypeHandler.from(method);
        List<ParameterInfo> params = ParameterInfo.from(method);
        return new MethodMetadata(method, functionName, handler, params);
    }
}
