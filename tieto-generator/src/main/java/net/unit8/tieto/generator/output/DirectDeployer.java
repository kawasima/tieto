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
public final class DirectDeployer {

    /**
     * A check to run inside the deploy transaction after the functions are created
     * but before commit (e.g. {@link SpecInjectionProbe}). Throwing rolls back the deploy.
     */
    @FunctionalInterface
    public interface DeployVerification {
        void verify(Connection conn);
    }

    /**
     * Deploys all generated functions in a single transaction, with no post-deploy checks.
     */
    public void deploy(Connection conn, List<GeneratedFunction> functions) {
        deploy(conn, functions, List.of());
    }

    /**
     * Deploys all generated functions in a single transaction, runs each verification
     * within that transaction, and commits only if every verification passes. A failure
     * anywhere — a CREATE, a verification, or the commit — rolls the whole deploy back, so
     * the database is never left half-migrated or with an unverified function.
     *
     * <p>The connection must own no pending work: this method takes over transaction
     * control. Do not pass a connection with an in-progress transaction.</p>
     *
     * @param conn the database connection (no transaction in progress)
     * @param functions the generated functions to deploy
     * @param verifications checks to run before commit
     */
    public void deploy(Connection conn, List<GeneratedFunction> functions, List<DeployVerification> verifications) {
        try {
            if (!conn.getAutoCommit()) {
                throw new GeneratorException(
                        "deploy requires a connection with no transaction in progress"
                                + " (autoCommit was false); it manages its own transaction");
            }
            conn.setAutoCommit(false);
        } catch (SQLException e) {
            throw new GeneratorException(
                    "Failed to start deploy transaction: " + e.getMessage(), e);
        }
        try {
            executeAll(conn, functions);
            for (DeployVerification verification : verifications) {
                verification.verify(conn);
            }
            conn.commit();
        } catch (SQLException e) {
            rollback(conn);
            throw new GeneratorException(
                    "Failed to commit deploy: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            rollback(conn);
            throw e;
        } finally {
            restoreAutoCommit(conn);
        }
    }

    private static void executeAll(Connection conn, List<GeneratedFunction> functions) {
        try (Statement stmt = conn.createStatement()) {
            for (GeneratedFunction func : functions) {
                stmt.execute(func.sqlBody());
            }
        } catch (SQLException e) {
            throw new GeneratorException(
                    "Failed to deploy function: " + e.getMessage(), e);
        }
    }

    private static void rollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            // The deploy already failed; nothing actionable on a rollback failure.
        }
    }

    private static void restoreAutoCommit(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            // Connection is about to be closed by the caller; ignore.
        }
    }
}
