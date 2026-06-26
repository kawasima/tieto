package net.unit8.tieto.generator.command;

import net.unit8.tieto.generator.parser.GeneratorException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
                + " WHERE routine_schema = current_schema() AND routine_name = ?";
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
     * Whether {@code sql} actually declares {@code CREATE [OR REPLACE] FUNCTION <name>},
     * rather than merely mentioning the name (e.g. in a comment, or as a prefix of a
     * longer function name).
     */
    static boolean declaredInFile(String sql, String functionName) {
        Pattern declaration = Pattern.compile(
                "(?i)CREATE\\s+(OR\\s+REPLACE\\s+)?FUNCTION\\s+\"?" + Pattern.quote(functionName) + "\"?\\b");
        return declaration.matcher(sql).find();
    }
}
