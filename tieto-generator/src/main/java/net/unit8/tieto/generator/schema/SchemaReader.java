package net.unit8.tieto.generator.schema;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads database schema information using JDBC DatabaseMetaData.
 */
public class SchemaReader {

    /** {@code null} means "resolve the connection's {@code current_schema()} at read time". */
    private final String schemaName;

    public SchemaReader() {
        this(null);
    }

    public SchemaReader(String schemaName) {
        this.schemaName = schemaName;
    }

    /**
     * Reads all tables and their metadata from the database. When no schema was given, the
     * connection's {@code current_schema()} is used — the same search-path-relative schema in
     * which the generator's existence checks and unqualified {@code CREATE} operate, so the
     * three never read a different schema than they write to.
     */
    public List<TableInfo> readSchema(Connection conn) throws SQLException {
        String schema = effectiveSchema(conn);
        List<TableInfo> tables = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();

        try (ResultSet rs = meta.getTables(null, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                List<ColumnInfo> columns = readColumns(meta, schema, tableName);
                List<String> primaryKeys = readPrimaryKeys(meta, schema, tableName);
                List<ForeignKeyInfo> foreignKeys = readForeignKeys(meta, schema, tableName);
                tables.add(new TableInfo(tableName, columns, primaryKeys, foreignKeys));
            }
        }
        return tables;
    }

    /** The configured schema, or the connection's {@code current_schema()} when none was set. */
    private String effectiveSchema(Connection conn) throws SQLException {
        if (schemaName != null) {
            return schemaName;
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_schema()")) {
            return rs.next() ? rs.getString(1) : "public";
        }
    }

    private List<ColumnInfo> readColumns(DatabaseMetaData meta, String schemaName, String tableName)
            throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(null, schemaName, tableName, "%")) {
            while (rs.next()) {
                columns.add(new ColumnInfo(
                        rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"),
                        "YES".equals(rs.getString("IS_NULLABLE")),
                        rs.getString("COLUMN_DEF"),
                        getIntegerOrNull(rs, "CHAR_OCTET_LENGTH")
                ));
            }
        }
        return columns;
    }

    private List<String> readPrimaryKeys(DatabaseMetaData meta, String schemaName, String tableName)
            throws SQLException {
        List<String> pks = new ArrayList<>();
        try (ResultSet rs = meta.getPrimaryKeys(null, schemaName, tableName)) {
            while (rs.next()) {
                pks.add(rs.getString("COLUMN_NAME"));
            }
        }
        return pks;
    }

    private List<ForeignKeyInfo> readForeignKeys(DatabaseMetaData meta, String schemaName, String tableName)
            throws SQLException {
        List<ForeignKeyInfo> fks = new ArrayList<>();
        try (ResultSet rs = meta.getImportedKeys(null, schemaName, tableName)) {
            while (rs.next()) {
                fks.add(new ForeignKeyInfo(
                        rs.getString("FKCOLUMN_NAME"),
                        rs.getString("PKTABLE_NAME"),
                        rs.getString("PKCOLUMN_NAME")
                ));
            }
        }
        return fks;
    }

    private static Integer getIntegerOrNull(ResultSet rs, String columnName)
            throws SQLException {
        int value = rs.getInt(columnName);
        return rs.wasNull() ? null : value;
    }
}
