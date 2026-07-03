package net.unit8.tieto.generator.output;

import net.unit8.tieto.generator.ai.GeneratedFunction;
import net.unit8.tieto.generator.parser.GeneratorException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link DirectDeployer} deploys functions atomically: a batch
 * that fails partway leaves the database unchanged.
 */
@Testcontainers(disabledWithoutDocker = true)
class DirectDeployerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static GeneratedFunction fn(String name, String sql) {
        return new GeneratedFunction(name, sql, null);
    }

    @Test
    void deploysValidFunctions() throws SQLException {
        try (Connection conn = newConnection()) {
            new DirectDeployer().deploy(conn, List.of(fn("ok_v1",
                    "CREATE OR REPLACE FUNCTION ok_v1() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$")));
        }
        assertThat(functionExists("ok_v1")).isTrue();
    }

    @Test
    void verifyOnlyRunsTheChecksButLeavesTheDatabaseUnchanged() throws SQLException {
        try (Connection conn = newConnection()) {
            // A verification that actually exercises the function (proving it was created) must
            // still leave nothing behind — verifyOnly always rolls back.
            new DirectDeployer().verifyOnly(
                    conn,
                    List.of(fn("verify_only_v1",
                            "CREATE OR REPLACE FUNCTION verify_only_v1() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$")),
                    List.of(c -> {
                        try (Statement stmt = c.createStatement();
                                ResultSet rs = stmt.executeQuery("SELECT verify_only_v1()")) {
                            rs.next();
                        } catch (SQLException e) {
                            throw new GeneratorException("probe failed", e);
                        }
                    }));
        }
        assertThat(functionExists("verify_only_v1"))
                .as("verifyOnly must not persist the function")
                .isFalse();
    }

    @Test
    void verifyOnlyThrowsAndPersistsNothingWhenAVerificationFails() throws SQLException {
        try (Connection conn = newConnection()) {
            assertThatThrownBy(() -> new DirectDeployer().verifyOnly(
                    conn,
                    List.of(fn("verify_fail_v1",
                            "CREATE OR REPLACE FUNCTION verify_fail_v1() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$")),
                    List.of(c -> {
                        throw new GeneratorException("signature mismatch");
                    })))
                    .isInstanceOf(GeneratorException.class);
        }
        assertThat(functionExists("verify_fail_v1"))
                .as("a failed verifyOnly leaves the database unchanged")
                .isFalse();
    }

    @Test
    void rollsBackTheWholeBatchWhenOneStatementFails() throws SQLException {
        try (Connection conn = newConnection()) {
            assertThatThrownBy(() -> new DirectDeployer().deploy(conn, List.of(
                    fn("good_v1",
                            "CREATE OR REPLACE FUNCTION good_v1() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$"),
                    fn("bad_v1",
                            "CREATE OR REPLACE FUNCTION bad_v1() RETURNS int LANGUAGE sql AS $$ THIS IS NOT SQL $$"))))
                    .isInstanceOf(GeneratorException.class);
        }

        assertThat(functionExists("good_v1"))
                .as("a function from a failed batch must be rolled back")
                .isFalse();
    }

    @Test
    void failsFastWhenTheConnectionAlreadyHasATransactionInProgress() throws SQLException {
        try (Connection conn = newConnection()) {
            conn.setAutoCommit(false);
            assertThatThrownBy(() -> new DirectDeployer().deploy(conn, List.of(fn("tx_v1",
                    "CREATE OR REPLACE FUNCTION tx_v1() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$"))))
                    .isInstanceOf(GeneratorException.class)
                    .hasMessageContaining("autoCommit");
        }
        assertThat(functionExists("tx_v1")).isFalse();
    }

    @Test
    void rollsBackWhenAPostDeployVerificationFails() throws SQLException {
        try (Connection conn = newConnection()) {
            assertThatThrownBy(() -> new DirectDeployer().deploy(
                    conn,
                    List.of(fn("verify_v1",
                            "CREATE OR REPLACE FUNCTION verify_v1() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$")),
                    List.of(c -> {
                        throw new GeneratorException("probe rejected the function");
                    })))
                    .isInstanceOf(GeneratorException.class)
                    .hasMessageContaining("probe rejected");
        }
        assertThat(functionExists("verify_v1"))
                .as("a function whose verification failed must be rolled back")
                .isFalse();
    }

    @Test
    void verificationSideEffectsAreRolledBackWhileTheFunctionsAreCommitted() throws SQLException {
        try (Connection setup = newConnection();
                Statement stmt = setup.createStatement()) {
            stmt.execute("CREATE TABLE probe_marker (id int)");
        }

        try (Connection conn = newConnection()) {
            // A verification that exercises a body with a side effect (here, a direct write to
            // stand in for a read-shaped function whose body mutates data) must not persist.
            new DirectDeployer().deploy(
                    conn,
                    List.of(fn("side_effect_v1",
                            "CREATE OR REPLACE FUNCTION side_effect_v1() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$")),
                    List.of(c -> {
                        try (Statement stmt = c.createStatement()) {
                            stmt.execute("INSERT INTO probe_marker VALUES (1)");
                        } catch (SQLException e) {
                            throw new GeneratorException("probe write failed", e);
                        }
                    }));
        }

        assertThat(functionExists("side_effect_v1"))
                .as("the CREATE is committed")
                .isTrue();
        assertThat(rowCount("probe_marker"))
                .as("the verification's side effect is rolled back, not committed")
                .isZero();
    }

    @Test
    void aToleratedErrorInOneVerificationDoesNotPoisonLaterVerifications() throws SQLException {
        try (Connection conn = newConnection()) {
            // Verification 1 runs a statement that errors (aborting the PG transaction) but
            // tolerates it by returning — exactly what ReadSmokeVerifier does for an INTO STRICT
            // no-row / out-of-range probe. Verification 2 must still run cleanly; with a single
            // shared savepoint it would fail with SQLSTATE 25P02 (transaction aborted).
            new DirectDeployer().deploy(
                    conn,
                    List.of(fn("poison_v1",
                            "CREATE OR REPLACE FUNCTION poison_v1() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$")),
                    List.of(
                            c -> {
                                try (Statement stmt = c.createStatement()) {
                                    stmt.execute("SELECT 1 / 0");   // aborts the transaction
                                } catch (SQLException tolerated) {
                                    // tolerated, like a non-body read-smoke error
                                }
                            },
                            c -> {
                                try (Statement stmt = c.createStatement();
                                        ResultSet rs = stmt.executeQuery("SELECT 1")) {
                                    rs.next();
                                } catch (SQLException e) {
                                    throw new GeneratorException(
                                            "later verification poisoned (SQLSTATE " + e.getSQLState() + ")", e);
                                }
                            }));
        }
        assertThat(functionExists("poison_v1"))
                .as("a tolerated abort in an earlier verification must not fail the deploy")
                .isTrue();
    }

    @Test
    void anErrorInAVerificationRollsBackTheDeploy() throws SQLException {
        try (Connection conn = newConnection()) {
            assertThatThrownBy(() -> new DirectDeployer().deploy(
                    conn,
                    List.of(fn("err_v1",
                            "CREATE OR REPLACE FUNCTION err_v1() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$")),
                    List.of(c -> {
                        throw new AssertionError("verifier blew up");
                    })))
                    .isInstanceOf(AssertionError.class);
        }
        assertThat(functionExists("err_v1"))
                .as("an Error in verification must roll back the deploy, not commit it")
                .isFalse();
    }

    private static int rowCount(String table) throws SQLException {
        try (Connection conn = newConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static boolean functionExists(String name) throws SQLException {
        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM information_schema.routines WHERE routine_name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
