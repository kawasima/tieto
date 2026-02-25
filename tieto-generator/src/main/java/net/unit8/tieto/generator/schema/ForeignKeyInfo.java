package net.unit8.tieto.generator.schema;

/**
 * Represents a foreign key relationship.
 */
public record ForeignKeyInfo(
        String columnName,
        String referencedTable,
        String referencedColumn
) {}
