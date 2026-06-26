package net.unit8.tieto.generator.parser;

/**
 * Represents a method parameter specification parsed from a Repository interface.
 *
 * @param name    the parameter name
 * @param type    the parameter type as written in source
 * @param typeDef the resolved domain/Specification type structure, or
 *                {@code null} for simple/JDK types
 */
public record ParameterSpec(String name, String type, TypeDef typeDef) {}
