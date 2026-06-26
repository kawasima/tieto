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
                The Specification arrives as ONE jsonb argument named spec holding a condition tree.
                Generate TWO functions:
                1. The MAIN function %1$s(...): build the WHERE clause from the spec tree and
                   EXECUTE the query with the spec BOUND as a parameter (USING spec), returning
                   each matching aggregate as JSONB (same nested shape as the other functions).
                2. A helper %1$s_spec_to_sql(node jsonb, path text[]) RETURNS text that
                   recursively translates one node into a boolean SQL expression.

                SECURITY — spec values must be BOUND, never concatenated (MANDATORY):
                - The helper MUST NOT put any spec value into the SQL text. It reads only
                  node->>'kind' (for dispatch); every leaf VALUE is referenced from the bound
                  spec BY PATH, so it travels as a bind parameter, not as text.
                - The main function binds the whole spec as $1 and EXECUTEs:
                    RETURN QUERY EXECUTE
                      'SELECT ... FROM orders o WHERE ' || %1$s_spec_to_sql(spec, '{}'::text[])
                      USING spec;
                - A leaf references its value as ($1 #>> <path>), building the path text[] with
                  format()'s %%L (the path, NOT the value, is what %%L quotes):
                    -- forCustomer:
                    RETURN format('customer_id = ($1 #>> %%L)', path || ARRAY['customerId']);
                    -- highValue (typed compare; o is the main query's table alias):
                    RETURN format('(SELECT SUM(quantity*unit_price) FROM order_lines l WHERE l.order_id = o.id) >= ($1 #>> %%L)::numeric', path || ARRAY['min']);
                - NEVER write '... = ' || (node->>'field'), and do NOT call quote_literal on a
                  spec value. The value must reach SQL only through the bound $1.

                Recursion (extend the path as you descend):
                - kind "and": AND-join %1$s_spec_to_sql(child, path || ARRAY['specs', i::text])
                  over each element i of node->'specs', wrapped in parentheses. Empty list -> TRUE.
                - kind "or": OR-join the same way. Empty -> FALSE.
                - kind "not": 'NOT (' || %1$s_spec_to_sql(node->'spec', path || ARRAY['spec']) || ')'.
                - A NULL node means "no condition" (TRUE).

                Worked example of the helper:
                CREATE OR REPLACE FUNCTION %1$s_spec_to_sql(node jsonb, path text[]) RETURNS text
                LANGUAGE plpgsql AS $$
                DECLARE k text := node->>'kind'; parts text[] := '{}'; child jsonb; i int := 0;
                BEGIN
                  IF node IS NULL THEN RETURN 'TRUE'; END IF;
                  IF k = 'and' THEN
                    FOR child IN SELECT jsonb_array_elements(node->'specs') LOOP
                      parts := parts || %1$s_spec_to_sql(child, path || ARRAY['specs', i::text]); i := i + 1;
                    END LOOP;
                    IF array_length(parts,1) IS NULL THEN RETURN 'TRUE'; END IF;
                    RETURN '(' || array_to_string(parts, ' AND ') || ')';
                  ELSIF k = 'forCustomer' THEN
                    RETURN format('customer_id = ($1 #>> %%L)', path || ARRAY['customerId']);
                  ELSE RAISE EXCEPTION 'unknown spec kind: %%', k; END IF;
                END; $$;

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
