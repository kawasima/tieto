package net.unit8.tieto.generator.command;

import net.unit8.tieto.generator.parser.GeneratorException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Drops a set of deployed functions in a single transaction. Mirrors {@code DirectDeployer}'s
 * discipline: it requires a connection with no transaction in progress, commits only if every
 * {@code DROP} succeeds, and rolls the whole batch back on any failure (a {@code Throwable}, so an
 * {@code Error} cannot escape to the {@code finally} and be implicitly committed by
 * {@code setAutoCommit(true)}).
 */
final class FunctionPruner {

    private FunctionPruner() {}

    /**
     * Drops each function by name and identity-argument list (so an overloaded function is dropped
     * unambiguously), all-or-nothing.
     *
     * @param conn a connection with no transaction in progress
     * @param targets the functions to drop
     */
    static void drop(Connection conn, List<FunctionInventory.Entry> targets) {
        try {
            if (!conn.getAutoCommit()) {
                throw new GeneratorException(
                        "prune requires a connection with no transaction in progress"
                                + " (autoCommit was false); it manages its own transaction");
            }
            conn.setAutoCommit(false);
        } catch (SQLException e) {
            throw new GeneratorException("Failed to start prune transaction: " + e.getMessage(), e);
        }
        try (Statement stmt = conn.createStatement()) {
            for (FunctionInventory.Entry target : targets) {
                stmt.execute("DROP FUNCTION IF EXISTS \"" + target.functionName()
                        + "\"(" + target.identityArgs() + ")");
            }
            conn.commit();
        } catch (SQLException e) {
            rollback(conn);
            throw new GeneratorException("Failed to prune functions: " + e.getMessage(), e);
        } catch (Throwable t) {
            rollback(conn);
            throw t;
        } finally {
            restoreAutoCommit(conn);
        }
    }

    private static void rollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            // The prune already failed; a rollback failure is not separately actionable.
        }
    }

    private static void restoreAutoCommit(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            // The connection is about to be closed by the caller; ignore.
        }
    }
}
