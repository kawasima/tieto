package net.unit8.tieto.core.function;

import java.lang.reflect.Method;

/**
 * Strategy for resolving the PostgreSQL function name from a Repository
 * interface and method.
 */
@FunctionalInterface
public interface FunctionNameResolver {

    /**
     * Resolves the PostgreSQL function name for the given repository method.
     *
     * @param repositoryInterface the Repository interface class
     * @param method the method being invoked
     * @return the PostgreSQL function name
     */
    String resolve(Class<?> repositoryInterface, Method method);
}
