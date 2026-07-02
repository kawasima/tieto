package net.unit8.tieto.generator.output;

import net.unit8.tieto.generator.ai.GeneratedFunction;
import net.unit8.tieto.generator.parser.GeneratorException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.List;

/**
 * Deploys generated SQL functions directly to a PostgreSQL database.
 */
public final class DirectDeployer {

    /**
     * A check to run inside the deploy transaction after the functions are created
     * but before commit (e.g. {@link SpecInjectionProbe}). Throwing rolls back the deploy.
     *
     * <p>A verification runs inside a savepoint that is always rolled back, so a check that
     * exercises a function body (a read smoke, an injection probe) never persists that body's
     * side effects when the deploy commits — only the {@code CREATE}s are committed.</p>
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
            verify(conn, verifications);
            conn.commit();
        } catch (SQLException e) {
            rollback(conn);
            throw new GeneratorException(
                    "Failed to commit deploy: " + e.getMessage(), e);
        } catch (Throwable t) {
            // Any failure — a verification's RuntimeException, or an Error — must roll the
            // deploy back. Without this, an Error would escape to the finally, where
            // restoreAutoCommit's setAutoCommit(true) implicitly commits the created functions.
            rollback(conn);
            throw t;
        } finally {
            restoreAutoCommit(conn);
        }
    }

    /**
     * Runs each verification inside its own savepoint that is always rolled back, so a
     * verification that exercises a function body (a read smoke, an injection probe) cannot
     * persist that body's side effects when the deploy commits — only the {@code CREATE}s, done
     * before any savepoint, survive.
     *
     * <p>A per-verification savepoint is also required for correctness, not just isolation:
     * {@code ReadSmokeVerifier} tolerates a non-body error (an {@code INTO STRICT} finding no
     * row, an out-of-range probe value) by returning normally, but that failed statement has
     * already put the transaction into the aborted state. Rolling back to this verification's
     * savepoint clears that state before the next verification runs; a single shared savepoint
     * would let the abort poison every later check with a {@code 25P02} error.</p>
     *
     * <p>A verification that throws still aborts the whole deploy (the caller rolls the
     * transaction back); the savepoint rollback first undoes anything it did, without masking
     * the original failure.</p>
     */
    private static void verify(Connection conn, List<DeployVerification> verifications) throws SQLException {
        for (DeployVerification verification : verifications) {
            Savepoint savepoint = conn.setSavepoint("tieto_verify");
            boolean verified = false;
            try {
                verification.verify(conn);
                verified = true;
            } finally {
                if (verified) {
                    // Success (or a tolerated error): undo any side effects and clear a possible
                    // aborted state so the next verification runs clean. If this rollback itself
                    // fails, let it propagate so the deploy aborts rather than committing.
                    conn.rollback(savepoint);
                } else {
                    // Already failing: do not let a savepoint-rollback error mask the original.
                    rollbackQuietly(conn, savepoint);
                }
            }
        }
    }

    private static void rollbackQuietly(Connection conn, Savepoint savepoint) {
        try {
            conn.rollback(savepoint);
        } catch (SQLException e) {
            // The caller rolls the whole transaction back; a failed savepoint rollback is not
            // separately actionable.
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
