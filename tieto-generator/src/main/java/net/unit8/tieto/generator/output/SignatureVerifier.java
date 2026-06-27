package net.unit8.tieto.generator.output;

import net.unit8.tieto.generator.parser.GeneratorException;
import net.unit8.tieto.generator.parser.MethodSpec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * Verifies that a deployed function's declared signature matches what the Java
 * Repository method implies, without invoking it.
 *
 * <p>The static {@link net.unit8.tieto.generator.ai.GeneratedSqlValidator} checks
 * that the generated SQL is structurally the expected {@code CREATE OR REPLACE
 * FUNCTION}, but it does not look at its declared types. A hallucinated
 * {@code RETURNS JSONB} for a {@code List<T>} method, a {@code RETURNS SETOF
 * JSONB} for a single-object method, a {@code RETURNS VOID} for a finder, or a
 * wrong argument count all pass the static check yet break at runtime.</p>
 *
 * <p>This compares only the parts of the signature that are unambiguous from the
 * method shape, so it cannot raise a false positive on a correctly generated
 * function:</p>
 * <ul>
 *   <li><b>SETOF-ness</b> — a {@code List<T>} method must be {@code RETURNS SETOF}
 *       (mirroring tieto-core, which supports {@code List<T>} as the only
 *       collection return); every other return must be non-SETOF.</li>
 *   <li><b>void-ness</b> — a {@code void} method must be {@code RETURNS VOID};
 *       any other method must not.</li>
 *   <li><b>arity</b> — the function must declare one argument per method
 *       parameter.</li>
 * </ul>
 *
 * <p>It deliberately does <em>not</em> assert the exact return base type (a
 * scalar method may return a native type rather than {@code jsonb}) or the
 * per-argument types: the generator's source-level type model and tieto-core's
 * runtime binding classify enums, generics, and externally-defined domain types
 * differently, so asserting jsonb-ness here would reject correctly generated
 * functions. Argument binding is covered behaviorally by the read smoke, the
 * injection probe, and the emitted round-trip test instead.</p>
 */
public final class SignatureVerifier {

    // tieto-core supports List<T> as the only collection return (ReturnTypeHandler);
    // Set/Collection/Iterable are not SETOF here, matching PromptBuilder's contract.
    private static final Pattern LIST_RETURN = Pattern.compile("(?:[\\w.]+\\.)?List\\s*<");

    /**
     * The signature a generated function must declare, derived from the Java method.
     *
     * @param returnsSet  whether the function must be {@code RETURNS SETOF} (List return)
     * @param returnsVoid whether the function must be {@code RETURNS VOID} (void method)
     * @param argCount    the number of declared arguments (method parameter count)
     */
    public record ExpectedSignature(boolean returnsSet, boolean returnsVoid, int argCount) {}

    /**
     * Derives the expected signature from a method spec. Pure; no database access.
     */
    public static ExpectedSignature expectedFor(MethodSpec method) {
        String returnType = method.returnType().strip();
        boolean returnsVoid = returnType.equals("void");
        boolean returnsSet = !returnsVoid && LIST_RETURN.matcher(returnType).lookingAt();
        return new ExpectedSignature(returnsSet, returnsVoid, method.parameters().size());
    }

    /**
     * Reads {@code functionName} from the catalog and throws if its declared
     * signature disagrees with {@code expected}.
     *
     * @param conn a connection in the deploy transaction (so an uncommitted
     *             function is visible)
     * @param functionName the deployed function name (lower case, unqualified)
     * @param expected the signature derived from the Java method
     */
    public void verify(Connection conn, String functionName, ExpectedSignature expected) {
        String sql = """
                SELECT p.proretset, rt.typname AS rettype, p.pronargs
                FROM pg_proc p
                JOIN pg_type rt ON rt.oid = p.prorettype
                WHERE p.proname = ? AND pg_function_is_visible(p.oid)""";
        boolean actualReturnsSet;
        boolean actualReturnsVoid;
        int actualArgCount;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, functionName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new GeneratorException(
                            "Signature check could not find function " + functionName
                                    + " after deploy.");
                }
                actualReturnsSet = rs.getBoolean("proretset");
                actualReturnsVoid = "void".equals(rs.getString("rettype"));
                actualArgCount = rs.getInt("pronargs");
                if (rs.next()) {
                    throw new GeneratorException(
                            "Signature check found more than one function named " + functionName
                                    + "; overloaded generated functions are not supported.");
                }
            }
        } catch (SQLException e) {
            throw new GeneratorException(
                    "Failed to read the signature of " + functionName + ": " + e.getMessage(), e);
        }

        if (actualReturnsSet != expected.returnsSet()) {
            throw mismatch(functionName,
                    (expected.returnsSet() ? "RETURNS SETOF (a List return)" : "a non-SETOF return"),
                    (actualReturnsSet ? "RETURNS SETOF" : "a non-SETOF return"));
        }
        if (actualReturnsVoid != expected.returnsVoid()) {
            throw mismatch(functionName,
                    (expected.returnsVoid() ? "RETURNS VOID (a void method)" : "a value return"),
                    (actualReturnsVoid ? "RETURNS VOID" : "a value return"));
        }
        if (actualArgCount != expected.argCount()) {
            throw mismatch(functionName, expected.argCount() + " argument(s)",
                    actualArgCount + " argument(s)");
        }
    }

    private static GeneratorException mismatch(String functionName, String expected, String actual) {
        return new GeneratorException(
                "Generated function " + functionName + " has the wrong signature: the Repository"
                        + " method implies " + expected + ", but the function declares " + actual
                        + ". Regenerate the function so its signature matches the method.");
    }
}
