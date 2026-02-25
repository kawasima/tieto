package net.unit8.tieto.generator.schema;

import java.util.List;

/**
 * Represents a database table with its columns, primary keys, and foreign keys.
 */
public record TableInfo(
        String name,
        List<ColumnInfo> columns,
        List<String> primaryKeys,
        List<ForeignKeyInfo> foreignKeys
) {}
