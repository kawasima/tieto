package net.unit8.tieto.generator.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Derives PostgreSQL function names from repository specs and detects name
 * collisions.
 *
 * <p>A function name is {@code {repository}_{method}_v{N}} in snake_case, with
 * no parameter-type discriminator. Centralizing the rule here keeps the prompt,
 * the deploy path, and the collision check in agreement.</p>
 */
public final class FunctionNaming {

    private FunctionNaming() {
    }

    /** The PostgreSQL function name for a repository method, e.g. {@code order_repository_find_by_id_v1}. */
    public static String functionName(RepositorySpec repo, MethodSpec method) {
        return camelToSnake(repo.simpleName()) + "_" + camelToSnake(method.name())
                + "_v" + method.version();
    }

    public static String camelToSnake(String camel) {
        return camel
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase();
    }

    /**
     * Fails loudly when two methods map to the same function name. Because the
     * name carries no parameter-type discriminator, overloaded methods at the
     * same version would otherwise silently {@code CREATE OR REPLACE} one
     * another on deploy with no warning. Same-named methods on distinct
     * {@code @FunctionVersion} values do not collide.
     */
    public static void checkNoCollisions(RepositorySpec repo) {
        Map<String, List<MethodSpec>> byFunctionName = new LinkedHashMap<>();
        for (MethodSpec method : repo.methods()) {
            byFunctionName.computeIfAbsent(functionName(repo, method), k -> new ArrayList<>())
                    .add(method);
        }
        List<String> collisions = byFunctionName.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + " <- " + e.getValue().stream()
                        .map(FunctionNaming::describe)
                        .collect(Collectors.joining(", ")))
                .toList();
        if (!collisions.isEmpty()) {
            throw new GeneratorException(
                    "Overloaded repository methods map to the same PostgreSQL function name "
                            + "(the name ignores parameter types). Give them distinct "
                            + "@FunctionVersion values or rename them:\n  "
                            + String.join("\n  ", collisions));
        }
    }

    private static String describe(MethodSpec method) {
        return method.name() + "(" + method.parameters().stream()
                .map(ParameterSpec::type)
                .collect(Collectors.joining(", ")) + ")";
    }
}
