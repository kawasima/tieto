package net.unit8.tieto.generator.command;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the database existence check: it sees a function in the current schema,
 * is not fooled by a same-named function in another schema, and reports absent ones.
 */
@Testcontainers(disabledWithoutDocker = true)
class DeployedFunctionsDbIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void deploy() throws SQLException {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE FUNCTION present_v1() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$");
            stmt.execute("CREATE SCHEMA other");
            stmt.execute("CREATE FUNCTION other.elsewhere_v1() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$");
            stmt.execute("CREATE PROCEDURE a_procedure_v1() LANGUAGE sql AS $$ SELECT 1 $$");
        }
    }

    @Test
    void existsInDatabase_trueForAFunctionInTheCurrentSchema() throws SQLException {
        try (Connection conn = newConnection()) {
            assertThat(DeployedFunctions.existsInDatabase(conn, "present_v1")).isTrue();
        }
    }

    @Test
    void existsInDatabase_falseForAnAbsentFunction() throws SQLException {
        try (Connection conn = newConnection()) {
            assertThat(DeployedFunctions.existsInDatabase(conn, "missing_v1")).isFalse();
        }
    }

    @Test
    void existsInDatabase_falseForAFunctionOnlyInAnotherSchema() throws SQLException {
        try (Connection conn = newConnection()) {
            assertThat(DeployedFunctions.existsInDatabase(conn, "elsewhere_v1")).isFalse();
        }
    }

    @Test
    void existsInDatabase_falseForAProcedureOfTheSameName() throws SQLException {
        // tieto only ever creates FUNCTIONs; a same-named PROCEDURE must not count.
        try (Connection conn = newConnection()) {
            assertThat(DeployedFunctions.existsInDatabase(conn, "a_procedure_v1")).isFalse();
        }
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
