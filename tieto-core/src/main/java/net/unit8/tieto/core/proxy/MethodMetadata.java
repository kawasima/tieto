package net.unit8.tieto.core.proxy;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Pre-analyzed metadata for a Repository method.
 *
 * <p>Captures the return type handler and parameter information so that
 * they don't need to be re-analyzed on every invocation.</p>
 */
public record MethodMetadata(
        Method method,
        ReturnTypeHandler returnTypeHandler,
        List<ParameterInfo> parameters
) {

    /**
     * Analyzes a method and creates its metadata.
     */
    public static MethodMetadata analyze(Class<?> repoInterface, Method method) {
        ReturnTypeHandler handler = ReturnTypeHandler.from(method);
        List<ParameterInfo> params = ParameterInfo.from(method);
        return new MethodMetadata(method, handler, params);
    }
}
