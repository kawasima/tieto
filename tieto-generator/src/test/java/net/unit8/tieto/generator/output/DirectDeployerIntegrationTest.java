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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link DirectDeployer} deploys functions atomically: a batch
 * that fails partway leaves the database unchanged.
 */
@Testcontainers
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
