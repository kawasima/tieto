package net.unit8.tieto.generator.output;

import net.unit8.tieto.generator.parser.ComponentDef;
import net.unit8.tieto.generator.parser.GeneratorException;
import net.unit8.tieto.generator.parser.TypeDef;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Behavioral SQL-injection check for a generated Specification query function.
 *
 * <p>Calls the deployed function with a probe spec whose leaf value is a single
 * quote {@code '}. If the function binds that value (the parameterized contract),
 * it is a harmless literal that matches nothing and the call succeeds. If the
 * function instead concatenates the value into the SQL text, the lone quote
 * unbalances the statement and PostgreSQL raises a syntax error — which is caught
 * and reported as an injection.</p>
 *
 * <p>Unlike a static text scan, this catches concatenation regardless of how the
 * value was extracted ({@code ->>}, {@code #>>}, {@code jsonb_extract_path_text},
 * a cast, an intermediate variable …), because it tests behavior, not syntax.</p>
 *
 * <p>Scope and limits:</p>
 * <ul>
 *   <li>One probe is produced per <em>String-typed</em> leaf field, each wrapped
 *       in an {@code and} so the recursion/path-threading is exercised too.</li>
 *   <li>Numeric/enum-only leaves are not probed: a non-numeric probe value fails
 *       the {@code ::numeric} cast even when correctly bound, so {@code '} cannot
 *       tell binding from concatenation there. Those leaves rely on the cast (an
 *       attacker's non-numeric value also fails it) and the static check.</li>
 *   <li>A safe leaf that <em>parses</em> the value (e.g. {@code to_tsquery},
 *       {@code ::tsquery}, a regex) can error on a bound {@code '} and be wrongly
 *       rejected; such leaves are uncommon for column predicates.</li>
 * </ul>
 */
public final class SpecInjectionProbe {

    private static final Set<String> COMPOSITE_KINDS = Set.of("and", "or", "not");

    /**
     * Builds a probe spec for every String-typed leaf field in the hierarchy, each
     * with that field set to a single quote and (when the hierarchy has an {@code and})
     * wrapped in an {@code and} so the composite path-threading runs. Empty if there is
     * no String-typed leaf to probe.
     */
    public static List<String> probeSpecsFor(TypeDef specType) {
        boolean hasAnd = hasKind(specType.subtypes(), "and");
        List<String> probes = new ArrayList<>();
        collectStringLeafProbes(specType.subtypes(), hasAnd, probes);
        return probes;
    }

    private static void collectStringLeafProbes(List<TypeDef> subtypes, boolean hasAnd, List<String> out) {
        for (TypeDef sub : subtypes) {
            String kind = sub.kind();
            if (kind != null && !COMPOSITE_KINDS.contains(kind)) {
                for (ComponentDef component : sub.components()) {
                    if (isStringType(component.type())) {
                        String leaf = "{\"kind\":\"" + kind + "\",\"" + component.name() + "\":\"'\"}";
                        out.add(hasAnd ? "{\"kind\":\"and\",\"specs\":[" + leaf + "]}" : leaf);
                    }
                }
            }
            if (!sub.subtypes().isEmpty()) {
                collectStringLeafProbes(sub.subtypes(), hasAnd, out);
            }
        }
    }

    private static boolean hasKind(List<TypeDef> subtypes, String kind) {
        for (TypeDef sub : subtypes) {
            if (kind.equals(sub.kind()) || hasKind(sub.subtypes(), kind)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStringType(String type) {
        return "String".equals(type) || "java.lang.String".equals(type);
    }

    /**
     * Calls {@code functionName} with {@code probeSpecJson} and throws if the call
     * raises a SQL error — meaning the probe value reached the SQL text instead of
     * being bound.
     *
     * @param conn a connection in the same transaction the functions were deployed in
     * @param functionName the generated main function name
     * @param probeSpecJson a probe spec from {@link #probeSpecsFor}
     */
    public void verify(Connection conn, String functionName, String probeSpecJson) {
        String sql = "SELECT * FROM " + functionName + "(CAST(? AS jsonb))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, probeSpecJson);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // drain
                }
            }
        } catch (SQLException e) {
            throw new GeneratorException(
                    "Injection probe failed for " + functionName + ": calling it with a"
                            + " single-quote leaf value raised \"" + e.getMessage().strip() + "\"."
                            + " A safe function binds the value (a harmless literal); this error"
                            + " means the value is concatenated into the SQL text. Regenerate the"
                            + " function so leaf values are referenced from the bound spec.");
        }
    }
}
