package net.unit8.tieto.generator.ai;

import net.unit8.tieto.generator.parser.MethodSpec;
import net.unit8.tieto.generator.parser.ParameterSpec;
import net.unit8.tieto.generator.parser.RepositorySpec;
import net.unit8.tieto.generator.schema.ColumnInfo;
import net.unit8.tieto.generator.schema.ForeignKeyInfo;
import net.unit8.tieto.generator.schema.TableInfo;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Constructs prompts for AI providers from repository specifications and database schema.
 */
public class PromptBuilder {

    /**
     * Builds a prompt for generating a single PostgreSQL function.
     */
    public String build(RepositorySpec repo, MethodSpec method, List<TableInfo> schema) {
        return """
                You are a PostgreSQL expert. Generate a PostgreSQL function based on the \
                following specification.

                ## Repository Interface
                Interface: %s

                ## Method
                Name: %s
                Return type: %s
                Parameters: %s

                ## Method Specification (from Javadoc)
                %s

                ## Database Schema
                %s

                ## Naming Convention
                Function name: %s

                ## Rules
                - Input domain objects are passed as JSONB parameters.
                - Simple types (Long, String, int, UUID, etc.) are passed as their \
                native PostgreSQL types.
                - For methods returning a list (List<T>), use RETURNS SETOF JSONB.
                - For methods returning a single object or Optional<T>, use RETURNS JSONB.
                - For void methods, use RETURNS VOID.
                - The returned JSONB must represent the full domain object with field \
                names matching the Java property names (camelCase).
                - Use CREATE OR REPLACE FUNCTION.
                - Language: plpgsql
                - Include appropriate error handling with RAISE EXCEPTION where needed.

                ## Output Format
                Return ONLY the complete SQL statement. No markdown fences, no explanation.
                """.formatted(
                repo.fullyQualifiedName(),
                method.name(),
                method.returnType(),
                formatParameters(method.parameters()),
                method.javadoc().isEmpty() ? "(no specification provided)" : method.javadoc(),
                formatSchema(schema),
                resolveFunctionName(repo, method)
        );
    }

    private String formatParameters(List<ParameterSpec> parameters) {
        if (parameters.isEmpty()) return "(none)";
        return parameters.stream()
                .map(p -> p.name() + ": " + p.type())
                .collect(Collectors.joining(", "));
    }

    private String formatSchema(List<TableInfo> schema) {
        var sb = new StringBuilder();
        for (TableInfo table : schema) {
            sb.append("### Table: ").append(table.name()).append('\n');
            sb.append("Columns:\n");
            for (ColumnInfo col : table.columns()) {
                sb.append("  - ").append(col.name())
                        .append(' ').append(col.dataType());
                if (!col.nullable()) sb.append(" NOT NULL");
                if (col.defaultValue() != null) sb.append(" DEFAULT ").append(col.defaultValue());
                sb.append('\n');
            }
            if (!table.primaryKeys().isEmpty()) {
                sb.append("Primary key: ").append(String.join(", ", table.primaryKeys())).append('\n');
            }
            if (!table.foreignKeys().isEmpty()) {
                sb.append("Foreign keys:\n");
                for (ForeignKeyInfo fk : table.foreignKeys()) {
                    sb.append("  - ").append(fk.columnName())
                            .append(" -> ").append(fk.referencedTable())
                            .append('.').append(fk.referencedColumn())
                            .append('\n');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String resolveFunctionName(RepositorySpec repo, MethodSpec method) {
        return camelToSnake(repo.simpleName()) + "_" + camelToSnake(method.name());
    }

    private static String camelToSnake(String camel) {
        return camel
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase();
    }
}
