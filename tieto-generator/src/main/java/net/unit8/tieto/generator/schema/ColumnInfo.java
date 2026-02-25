package net.unit8.tieto.generator.schema;

/**
 * Represents a column in a database table.
 */
public record ColumnInfo(
        String name,
        String dataType,
        boolean nullable,
        String defaultValue,
        Integer characterMaxLength
) {}
