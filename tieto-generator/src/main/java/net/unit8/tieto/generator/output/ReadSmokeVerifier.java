package net.unit8.tieto.generator.output;

import net.unit8.tieto.generator.parser.GeneratorException;
import net.unit8.tieto.generator.parser.MethodSpec;
import net.unit8.tieto.generator.parser.ParameterSpec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Smoke-invokes a read-only generated function with synthesized arguments and
 * fails if the call raises a SQL error.
 *
 * <p>PostgreSQL only parses a plpgsql body at {@code CREATE} time; it resolves
 * the table and column references in the embedded SQL on first execution. So a
 * function that selects a non-existent column, references the wrong table, or
 * builds malformed JSONB passes both the static validator and {@code CREATE},
 * and fails only when first called. Calling it once — inside the deploy
 * transaction, which is rolled back on failure — surfaces that class of error
 * before the function is committed.</p>
 *
 * <p>Only methods that are safe to call blindly are smoked: a read (non-void
 * return, no write-verb name) whose every parameter is a simple JDK type we can
 * synthesize. Methods taking a domain or Specification ({@code jsonb}) argument
 * are excluded — synthesizing a valid aggregate that satisfies foreign keys and
 * constraints is not generally possible, and a constraint violation on synthetic
 * data would be a false rejection. Writes are excluded for the same reason and
 * because their effect is not the point of a read smoke. Zero rows is a pass:
 * the goal is that the query plans and runs, not that it matches anything.</p>
 */
public final class ReadSmokeVerifier {

    /** Method-name prefixes that denote a write, which is never read-smoked. */
    private static final Set<String> WRITE_PREFIXES = Set.of(
            "save", "insert", "update", "delete", "remove", "create",
            "upsert", "persist", "store", "merge", "add", "put");

    private static final UUID PROBE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final LocalDate PROBE_DATE = LocalDate.of(2000, 1, 1);
    private static final LocalTime PROBE_TIME = LocalTime.of(0, 0);
    private static final LocalDateTime PROBE_DATETIME = LocalDateTime.of(PROBE_DATE, PROBE_TIME);

    /**
     * Simple parameter types we can synthesize a harmless probe value for, keyed
     * by simple type name. {@code Instant}/{@code ZonedDateTime}/{@code char} are
     * deliberately absent: pgjdbc cannot bind the first two via {@code setObject},
     * and {@code char} binding is unreliable — a method taking one is left
     * un-smoked rather than risk a false rejection.
     */
    private static final Map<String, Supplier<Object>> SYNTHESIZERS = Map.ofEntries(
            Map.entry("long", () -> 0L), Map.entry("Long", () -> 0L),
            Map.entry("int", () -> 0), Map.entry("Integer", () -> 0),
            Map.entry("short", () -> (short) 0), Map.entry("Short", () -> (short) 0),
            Map.entry("byte", () -> (byte) 0), Map.entry("Byte", () -> (byte) 0),
            Map.entry("double", () -> 0.0d), Map.entry("Double", () -> 0.0d),
            Map.entry("float", () -> 0.0f), Map.entry("Float", () -> 0.0f),
            Map.entry("boolean", () -> false), Map.entry("Boolean", () -> false),
            Map.entry("BigDecimal", () -> BigDecimal.ZERO),
            Map.entry("BigInteger", () -> BigInteger.ZERO),
            Map.entry("String", () -> "tieto-smoke"),
            Map.entry("CharSequence", () -> "tieto-smoke"),
            Map.entry("UUID", () -> PROBE_UUID),
            Map.entry("LocalDate", () -> PROBE_DATE),
            Map.entry("LocalTime", () -> PROBE_TIME),
            Map.entry("LocalDateTime", () -> PROBE_DATETIME),
            Map.entry("OffsetDateTime", () -> OffsetDateTime.of(PROBE_DATETIME, ZoneOffset.UTC)));

    /**
     * Whether the method looks like a read: it returns something and its name
     * does not start with a write verb. Such methods are the smoke candidates;
     * those with a non-synthesizable parameter are reported (not smoked) so the
     * skip is visible rather than silent.
     */
    public static boolean isReadShaped(MethodSpec method) {
        return !method.returnType().strip().equals("void") && !isWriteName(method.name());
    }

    /**
     * Whether a method name starts with a write verb. The single owner of the
     * read/write naming heuristic, shared with {@link RepositoryTestGenerator} so
     * the deploy smoke and the emitted test classify a method the same way.
     */
    public static boolean isWriteName(String methodName) {
        String name = methodName.toLowerCase();
        return WRITE_PREFIXES.stream().anyMatch(name::startsWith);
    }

    /**
     * Whether the method can be safely smoke-invoked: it is read-shaped and every
     * parameter is a simple type we can synthesize (no domain/Specification jsonb
     * argument, no unsupported temporal/char type).
     */
    public static boolean isEligible(MethodSpec method) {
        return isReadShaped(method)
                && method.parameters().stream().allMatch(ReadSmokeVerifier::isSynthesizable);
    }

    /**
     * The simple type names the smoke can synthesize a probe value for. Exposed so
     * a test can assert it matches {@link RepositoryTestGenerator}'s renderable set,
     * keeping the deploy smoke and the emitted test in step.
     */
    static Set<String> synthesizableTypeNames() {
        return SYNTHESIZERS.keySet();
    }

    private static boolean isSynthesizable(ParameterSpec parameter) {
        return parameter.typeDef() == null && SYNTHESIZERS.containsKey(simpleName(parameter.type()));
    }

    private static String simpleName(String type) {
        String t = type.strip();
        int lt = t.indexOf('<');
        if (lt >= 0) {
            t = t.substring(0, lt);
        }
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    /**
     * The probe argument values for an eligible method, in declaration order.
     * Exposed for testing; {@link #verify} uses it internally.
     */
    public static List<Object> probeArguments(MethodSpec method) {
        return method.parameters().stream()
                .map(p -> SYNTHESIZERS.get(simpleName(p.type())).get())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * Calls {@code functionName} with synthesized arguments and throws if it
     * raises a SQL error.
     *
     * @param conn a connection in the deploy transaction
     * @param functionName the deployed function name
     * @param method the method the function was generated for (must be
     *               {@link #isEligible eligible})
     */
    public void verify(Connection conn, String functionName, MethodSpec method) {
        List<Object> args = probeArguments(method);
        String placeholders = args.isEmpty() ? "" : "?" + ", ?".repeat(args.size() - 1);
        String sql = "SELECT * FROM " + functionName + "(" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // drain; zero or more rows are all fine
                }
            }
        } catch (SQLException e) {
            if (!isBodyResolutionError(e)) {
                // The body planned and ran; the error is about the synthesized probe value,
                // not a broken reference — e.g. INTO STRICT finding no row (P0002), or the
                // value not matching a column's type/enum/check (class 22). Not a body bug;
                // a behavioral correctness check is the emitted round-trip test's job.
                return;
            }
            throw new GeneratorException(
                    "Read smoke failed for " + functionName + ": calling it with synthesized"
                            + " arguments raised \"" + e.getMessage().strip() + "\". CREATE only"
                            + " parses the body; the first call resolves its table/column"
                            + " references, so this means the function body is broken (e.g. a"
                            + " missing column or wrong table). Regenerate the function.");
        }
    }

    /**
     * Whether a SQL error indicates a broken function body (an unresolved table,
     * column, function, or type) rather than the synthesized probe value being
     * rejected by the data. Body-resolution errors are PostgreSQL SQLSTATE class
     * 42 (syntax error or access rule violation); errors from the probe value —
     * {@code no_data_found} from {@code INTO STRICT}, or class 22 data exceptions
     * such as an invalid enum/numeric input — are not body bugs. A missing
     * SQLSTATE is treated as a body error, failing closed.
     */
    private static boolean isBodyResolutionError(SQLException e) {
        String state = e.getSQLState();
        return state == null || state.startsWith("42");
    }
}
