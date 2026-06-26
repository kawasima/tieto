package net.unit8.tieto.generator.ai;

import net.unit8.tieto.generator.parser.ComponentDef;
import net.unit8.tieto.generator.parser.MethodSpec;
import net.unit8.tieto.generator.parser.ParameterSpec;
import net.unit8.tieto.generator.parser.RepositorySpec;
import net.unit8.tieto.generator.parser.TypeDef;
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
        String functionName = resolveFunctionName(repo, method);
        return """
                You are a PostgreSQL expert. Generate the PostgreSQL function(s) based on the \
                following specification.

                ## Repository Interface
                Interface: %s

                ## Method
                Name: %s
                Return type: %s
                Parameters: %s

                ## Method Specification (from Javadoc)
                %s
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
                %s
                ## Output Format
                Return ONLY the complete SQL statement(s). No markdown fences, no explanation.
                """.formatted(
                repo.fullyQualifiedName(),
                method.name(),
                method.returnType(),
                formatParameters(method.parameters()),
                method.javadoc().isEmpty() ? "(no specification provided)" : method.javadoc(),
                formatParameterTypes(method.parameters()),
                formatSchema(schema),
                functionName,
                specRules(method, functionName)
        );
    }

    private String formatParameters(List<ParameterSpec> parameters) {
        if (parameters.isEmpty()) return "(none)";
        return parameters.stream()
                .map(p -> p.name() + ": " + p.type())
                .collect(Collectors.joining(", "));
    }

    /**
     * Describes resolved domain parameter types. Sealed Specification hierarchies
     * get a full tree description so the AI can derive conditions from the model.
     */
    private String formatParameterTypes(List<ParameterSpec> parameters) {
        var sb = new StringBuilder();
        for (ParameterSpec p : parameters) {
            TypeDef def = p.typeDef();
            if (def == null) continue;
            if (def.sealed()) {
                sb.append("\n## Specification Parameter: ").append(p.name())
                        .append(" (").append(def.simpleName()).append(")\n");
                sb.append("This parameter is a COMPOSABLE Specification. It arrives as a single ")
                        .append("JSONB argument encoding a condition tree. Every node is a JSON object ")
                        .append("with a \"kind\" discriminator (camelCase of the type name); the node's ")
                        .append("other keys are its fields (camelCase).\n");
                if (!def.javadoc().isBlank()) {
                    sb.append("Description: ").append(def.javadoc().strip()).append('\n');
                }
                sb.append("Node kinds:\n");
                appendKinds(sb, def.subtypes());
            } else {
                sb.append("\n## Parameter Type: ").append(p.name())
                        .append(" (").append(def.simpleName()).append(")\n");
                sb.append("Fields: ").append(formatComponents(def.components())).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Renders every node kind in the (possibly nested) sealed hierarchy. The SQL
     * helper dispatches on "kind" regardless of nesting depth, so deeper
     * subtypes are flattened into the same list.
     */
    private void appendKinds(StringBuilder sb, List<TypeDef> subtypes) {
        for (TypeDef sub : subtypes) {
            sb.append("  - kind=\"").append(sub.kind()).append("\"");
            sb.append(" fields: ").append(formatComponents(sub.components()));
            if (!sub.javadoc().isBlank()) {
                sb.append("  // ").append(sub.javadoc().strip().replaceAll("\\s+", " "));
            }
            sb.append('\n');
            if (!sub.subtypes().isEmpty()) {
                appendKinds(sb, sub.subtypes());
            }
        }
    }

    private String formatComponents(List<ComponentDef> components) {
        if (components.isEmpty()) return "(none)";
        return components.stream()
                .map(c -> c.name() + ": " + c.type())
                .collect(Collectors.joining(", "));
    }

    /**
     * Extra generation rules when a method takes a composable Specification.
     */
    private String specRules(MethodSpec method, String functionName) {
        boolean hasSpec = method.parameters().stream()
                .anyMatch(p -> p.typeDef() != null && p.typeDef().sealed());
        if (!hasSpec) {
            return "";
        }
        return """

                ## Specification Rules (composable query conditions)
                The Specification arrives as ONE jsonb argument holding a condition tree.
                Generate TWO functions:
                1. The main function %1$s(...) that builds the WHERE clause from the spec
                   tree and returns each matching aggregate as JSONB (same nested shape as
                   the other functions for this entity).
                2. A helper function %1$s_spec_to_sql(spec jsonb) RETURNS text that
                   recursively translates one spec node into a boolean SQL expression.
                Translation rules for the helper:
                - kind "and": AND-join %1$s_spec_to_sql over each element of spec->'specs',
                  wrapped in parentheses. An empty list yields TRUE.
                - kind "or": OR-join over spec->'specs', wrapped in parentheses. Empty yields FALSE.
                - kind "not": 'NOT (' || %1$s_spec_to_sql(spec->'spec') || ')'.
                - any other kind: a LEAF predicate. Derive its SQL condition from the kind
                  name, its fields, and the schema (map the domain predicate to the right
                  column(s) or aggregate expression).

                SECURITY — embedding spec field values (MANDATORY):
                - NEVER concatenate a spec value directly into the SQL string. Do NOT write
                  '... = ' || (spec->>'field') — that is a SQL-injection hole reachable from
                  a normal repository call.
                - Build every leaf condition with format(), using %%L for VALUES and %%I for
                  IDENTIFIERS. Examples:
                    format('customer_id = %%L', spec->>'customerId')
                    format('SUM(quantity * unit_price) >= %%L', (spec->>'min')::numeric)
                - If you must use || concatenation, wrap every value in quote_literal() and
                  every identifier in quote_ident():
                    'customer_id = ' || quote_literal(spec->>'customerId')
                - %%L / quote_literal() also handle NULLs and embedded quotes correctly.
                - It is fine to read spec->>'kind' for dispatching and to pass spec->'specs' /
                  spec->'spec' to the recursive helper; only raw value concatenation is unsafe.

                - A NULL or absent spec means "no condition" (TRUE).
                Output the MAIN function FIRST, then the helper function.
                """.formatted(functionName);
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
        return camelToSnake(repo.simpleName()) + "_" + camelToSnake(method.name())
                + "_v" + method.version();
    }

    private static String camelToSnake(String camel) {
        return camel
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase();
    }
}
