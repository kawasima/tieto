package net.unit8.tieto.core.function;

import net.unit8.tieto.core.StubDataSource;
import net.unit8.tieto.core.TietoClient;
import net.unit8.tieto.core.exception.FunctionCallException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that a single-valued ({@code T}) or {@code Optional<T>} method rejects a
 * function that returns more than one row, rather than silently dropping the extras.
 */
@Testcontainers
class NonUniqueResultIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    interface DupRepository {
        Long one();
        Optional<Long> maybeOne();
    }

    @BeforeAll
    static void deployFunctions() throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE OR REPLACE FUNCTION dup_repository_one_v1()
                    RETURNS SETOF jsonb LANGUAGE sql AS
                    $$ SELECT * FROM (VALUES (to_jsonb(1)), (to_jsonb(2))) v(j) $$
                    """);
            stmt.execute("""
                    CREATE OR REPLACE FUNCTION dup_repository_maybe_one_v1()
                    RETURNS SETOF jsonb LANGUAGE sql AS
                    $$ SELECT * FROM (VALUES (to_jsonb(1)), (to_jsonb(2))) v(j) $$
                    """);
        }
    }

    private DupRepository repo() {
        StubDataSource ds = new StubDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            }
        };
        return TietoClient.builder(ds).build().createRepository(DupRepository.class);
    }

    @Test
    void singleResultMethodRejectsMultipleRows() {
        assertThatThrownBy(() -> repo().one())
                .isInstanceOf(FunctionCallException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    void optionalResultMethodRejectsMultipleRows() {
        assertThatThrownBy(() -> repo().maybeOne())
                .isInstanceOf(FunctionCallException.class)
                .hasMessageContaining("more than one");
    }
}
