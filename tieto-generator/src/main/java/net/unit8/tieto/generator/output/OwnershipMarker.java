package net.unit8.tieto.generator.output;

/**
 * The provenance marker tieto attaches to every function it generates:
 * {@code COMMENT ON FUNCTION <name> IS 'tieto:generated repository=… method=… version=…'}.
 *
 * <p>tieto emits it deterministically (after the AI output has been validated — the AI is never
 * asked to write it), so ownership does not depend on the model getting a signature right. The
 * comment is what lets {@code functions list}/{@code prune} tell a tieto-managed function from a
 * hand-written one that merely matches the {@code {repo}_{method}_v{N}} naming shape, so prune never
 * drops SQL it did not generate. The argument list is omitted from the {@code COMMENT} — tieto
 * function names are unique per version, so PostgreSQL resolves the name unambiguously without it.</p>
 */
public final class OwnershipMarker {

    /** The prefix every tieto ownership comment starts with. */
    public static final String PREFIX = "tieto:generated";

    private OwnershipMarker() {}

    /**
     * The {@code COMMENT ON FUNCTION} statement(s) to append to a generated function's SQL — one for
     * the function, plus one for its {@code _spec_to_sql} helper when the method takes a Specification.
     */
    public static String forFunction(String repositorySimpleName, String methodName, int version,
                                     String functionName, boolean withSpecHelper) {
        StringBuilder sb = new StringBuilder();
        sb.append(comment(functionName, repositorySimpleName, methodName, version));
        if (withSpecHelper) {
            sb.append('\n').append(comment(functionName + "_spec_to_sql", repositorySimpleName, methodName, version));
        }
        return sb.toString();
    }

    /** Whether a {@code pg_proc} comment marks the function as tieto-generated. */
    public static boolean isManaged(String comment) {
        return comment != null && comment.startsWith(PREFIX);
    }

    private static String comment(String functionName, String repo, String method, int version) {
        return "COMMENT ON FUNCTION " + functionName + " IS '" + PREFIX
                + " repository=" + repo + " method=" + method + " version=" + version + "';";
    }
}
