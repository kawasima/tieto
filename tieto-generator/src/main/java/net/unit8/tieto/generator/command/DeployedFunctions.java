package net.unit8.tieto.generator.command;

import net.unit8.tieto.generator.parser.GeneratorException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Looks up whether a generated function already exists, for the
 * skip-if-present idempotency check.
 */
final class DeployedFunctions {

    private DeployedFunctions() {}

    /**
     * Whether a function of {@code functionName} already exists in the connection's
     * current schema (where an unqualified CREATE FUNCTION would land).
     *
     * @throws GeneratorException if the existence query fails — a connectivity or
     *         permission error must not be mistaken for "absent" (which would cause
     *         an unintended regenerate/overwrite).
     */
    static boolean existsInDatabase(Connection conn, String functionName) {
        String sql = "SELECT 1 FROM information_schema.routines"
                + " WHERE routine_schema = current_schema()"
                + " AND routine_type = 'FUNCTION' AND routine_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, functionName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new GeneratorException(
                    "Failed to check whether function " + functionName + " already exists: "
                            + e.getMessage(), e);
        }
    }

    /**
     * A function deployed in the database: its name, its identity argument list (for DROP), and its
     * {@code pg_proc} comment ({@code null} if none) — tieto stamps an ownership marker there.
     */
    record DeployedFunction(String name, String identityArgs, String comment) {}

    /**
     * Lists the functions in the connection's current schema whose name starts with {@code prefix}
     * (matched literally with {@code starts_with}, so the prefix's own {@code _} is not treated as a
     * LIKE wildcard), with each function's identity argument list (so an overloaded function can be
     * dropped unambiguously) and its comment (the tieto ownership marker, if any). Ordered by name.
     */
    static List<DeployedFunction> listByPrefix(Connection conn, String prefix) {
        String sql = "SELECT p.proname, pg_get_function_identity_arguments(p.oid) AS args,"
                + " obj_description(p.oid, 'pg_proc') AS comment"
                + " FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE n.nspname = current_schema() AND starts_with(p.proname, ?)"
                + " ORDER BY p.proname, args";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, prefix);
            try (ResultSet rs = ps.executeQuery()) {
                List<DeployedFunction> functions = new ArrayList<>();
                while (rs.next()) {
                    functions.add(new DeployedFunction(
                            rs.getString("proname"), rs.getString("args"), rs.getString("comment")));
                }
                return functions;
            }
        } catch (SQLException e) {
            throw new GeneratorException(
                    "Failed to list deployed functions with prefix " + prefix + ": " + e.getMessage(), e);
        }
    }

    /**
     * Whether {@code sql} actually declares {@code CREATE [OR REPLACE] FUNCTION <name>},
     * rather than merely mentioning the name (e.g. in a comment, as a prefix of a longer
     * function name, or inside a function body / EXECUTE string). The match is anchored
     * to the start of a line, so a {@code CREATE FUNCTION} embedded mid-line (in a comment
     * or a quoted string) is not counted.
     */
    static boolean declaredInFile(String sql, String functionName) {
        Pattern declaration = Pattern.compile(
                "(?im)^\\s*CREATE\\s+(OR\\s+REPLACE\\s+)?FUNCTION\\s+\"?"
                        + Pattern.quote(functionName) + "\"?\\b");
        return declaration.matcher(sql).find();
    }
}
