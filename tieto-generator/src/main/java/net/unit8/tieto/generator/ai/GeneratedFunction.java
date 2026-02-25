package net.unit8.tieto.generator.ai;

/**
 * Represents a generated PostgreSQL function.
 */
public record GeneratedFunction(
        String functionName,
        String sqlBody,
        String testSql
) {}
