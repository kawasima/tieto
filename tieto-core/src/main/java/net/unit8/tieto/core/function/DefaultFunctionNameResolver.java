package net.unit8.tieto.core.function;

import net.unit8.tieto.core.annotation.FunctionVersion;

import java.lang.reflect.Method;

/**
 * Default naming strategy that converts the Repository interface name and
 * method name to snake_case, with a version suffix.
 *
 * <p>Example: {@code OrderRepository.findByCustomerId} (v1)
 * &rarr; {@code order_repository_find_by_customer_id_v1}</p>
 */
public final class DefaultFunctionNameResolver implements FunctionNameResolver {

    @Override
    public String resolve(Class<?> repositoryInterface, Method method) {
        String repoName = camelToSnake(repositoryInterface.getSimpleName());
        String methodName = camelToSnake(method.getName());
        int version = resolveVersion(method);
        return repoName + "_" + methodName + "_v" + version;
    }

    private static int resolveVersion(Method method) {
        FunctionVersion fv = method.getAnnotation(FunctionVersion.class);
        return (fv != null) ? fv.value() : 1;
    }

    static String camelToSnake(String camel) {
        return camel
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase();
    }
}
