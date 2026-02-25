package net.unit8.tieto.core.function;

import java.lang.reflect.Method;

/**
 * Default naming strategy that converts the Repository interface name and
 * method name to snake_case.
 *
 * <p>Example: {@code OrderRepository.findByCustomerId}
 * &rarr; {@code order_repository_find_by_customer_id}</p>
 */
public final class DefaultFunctionNameResolver implements FunctionNameResolver {

    @Override
    public String resolve(Class<?> repositoryInterface, Method method) {
        String repoName = camelToSnake(repositoryInterface.getSimpleName());
        String methodName = camelToSnake(method.getName());
        return repoName + "_" + methodName;
    }

    static String camelToSnake(String camel) {
        return camel
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase();
    }
}
