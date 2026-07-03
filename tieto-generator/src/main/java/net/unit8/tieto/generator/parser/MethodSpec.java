package net.unit8.tieto.generator.parser;

import java.util.List;

/**
 * Represents a Repository method specification including its Javadoc description.
 */
public record MethodSpec(
        String name,
        String returnType,
        List<ParameterSpec> parameters,
        String javadoc,
        int version
) {
    /**
     * Whether the method takes a composable Specification (a sealed type), in which case the
     * generator also emits a {@code _spec_to_sql} helper for it. Mirrors the condition in
     * {@code PromptBuilder.specRules}.
     */
    public boolean hasSpecParameter() {
        return parameters.stream().anyMatch(p -> p.typeDef() != null && p.typeDef().sealed());
    }
}
