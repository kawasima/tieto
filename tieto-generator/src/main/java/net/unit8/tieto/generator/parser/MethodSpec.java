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
) {}
