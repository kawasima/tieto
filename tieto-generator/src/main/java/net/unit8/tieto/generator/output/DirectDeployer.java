package net.unit8.tieto.generator.output;

import net.unit8.tieto.generator.ai.GeneratedFunction;
import net.unit8.tieto.generator.parser.GeneratorException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Deploys generated SQL functions directly to a PostgreSQL database.
 */
public class DirectDeployer {

    /**
     * Deploys all generated functions in a single transaction, so a failure
     * partway through rolls back rather than leaving the database half-migrated.
     *
     * @param conn the database connection
     * @param functions the generated functions to deploy
     */
    public void deploy(Connection conn, List<GeneratedFunction> functions) {
        boolean previousAutoCommit;
        try {
            previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
        } catch (SQLException e) {
            throw new GeneratorException(
                    "Failed to start deploy transaction: " + e.getMessage(), e);
        }
        try (Statement stmt = conn.createStatement()) {
            for (GeneratedFunction func : functions) {
                stmt.execute(func.sqlBody());
            }
            conn.commit();
        } catch (SQLException e) {
            rollback(conn);
            throw new GeneratorException(
                    "Failed to deploy function: " + e.getMessage(), e);
        } finally {
            restoreAutoCommit(conn, previousAutoCommit);
        }
    }

    private static void rollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            // The deploy already failed; nothing actionable on a rollback failure.
        }
    }

    private static void restoreAutoCommit(Connection conn, boolean previousAutoCommit) {
        try {
            conn.setAutoCommit(previousAutoCommit);
        } catch (SQLException e) {
            // Connection is about to be closed by the caller; ignore.
        }
    }
}
