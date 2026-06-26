package net.unit8.tieto.generator.output;

import net.unit8.tieto.generator.parser.ComponentDef;
import net.unit8.tieto.generator.parser.GeneratorException;
import net.unit8.tieto.generator.parser.TypeDef;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 */
public final class SpecInjectionProbe {

    private static final Set<String> COMPOSITE_KINDS = Set.of("and", "or", "not");

    /**
     * Builds a probe spec JSON for a leaf kind that has a String field, with that
     * field set to a single quote. Returns null if the hierarchy has no string-typed
     * leaf to probe (in which case the caller falls back to the static check).
     */
    public static String probeSpecFor(TypeDef specType) {
        return findStringLeafProbe(specType.subtypes());
    }

    private static String findStringLeafProbe(List<TypeDef> subtypes) {
        for (TypeDef sub : subtypes) {
            String kind = sub.kind();
            if (kind != null && !COMPOSITE_KINDS.contains(kind)) {
                for (ComponentDef component : sub.components()) {
                    if (isStringType(component.type())) {
                        return "{\"kind\":\"" + kind + "\",\"" + component.name() + "\":\"'\"}";
                    }
                }
            }
            if (!sub.subtypes().isEmpty()) {
                String nested = findStringLeafProbe(sub.subtypes());
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
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
     * @param probeSpecJson a probe spec from {@link #probeSpecFor}
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
