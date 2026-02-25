package net.unit8.tieto.generator.parser;

import java.util.List;

/**
 * Represents a parsed Repository interface with all its method specifications.
 */
public record RepositorySpec(
        String fullyQualifiedName,
        String simpleName,
        List<MethodSpec> methods
) {}
