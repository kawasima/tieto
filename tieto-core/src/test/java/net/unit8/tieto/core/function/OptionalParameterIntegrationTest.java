package net.unit8.tieto.core.function;

import net.unit8.tieto.core.StubDataSource;
import net.unit8.tieto.core.TietoClient;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that an {@code Optional<E>} method parameter is unwrapped and bound as
 * {@code E}: a present Optional binds its value, an empty Optional binds SQL NULL.
 * The function reports {@code p_value IS NULL} so the binding is observed directly.
 */
@Testcontainers
class OptionalParameterIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    interface OptionalRepository {
        Boolean wasNull(Optional<String> value);
    }

    @BeforeAll
    static void deployFunction() throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE OR REPLACE FUNCTION optional_repository_was_null_v1(p_value text)
                    RETURNS jsonb LANGUAGE sql AS $$ SELECT to_jsonb(p_value IS NULL) $$
                    """);
        }
    }

    private OptionalRepository repo() {
        return TietoClient.builder(StubDataSource.opening(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                .build().createRepository(OptionalRepository.class);
    }

    @Test
    void presentOptionalBindsItsValue() {
        assertThat(repo().wasNull(Optional.of("hello"))).isFalse();
    }

    @Test
    void emptyOptionalBindsSqlNull() {
        assertThat(repo().wasNull(Optional.empty())).isTrue();
    }
}
