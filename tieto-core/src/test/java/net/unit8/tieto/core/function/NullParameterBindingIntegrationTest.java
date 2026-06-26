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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code null} argument is bound as SQL NULL for both domain (jsonb) and scalar
 * parameters.
 *
 * <p>This guards the concern that {@code setNull(Types.NULL)} could raise
 * "could not determine data type of parameter". It does not, because tieto always
 * invokes functions as {@code SELECT * FROM function_name(?, ...)}: the function
 * signature supplies each parameter's type, so an untyped null resolves. (The
 * error only arises with polymorphic operators like {@code WHERE col = ?}, which
 * tieto never generates.)</p>
 */
@Testcontainers
class NullParameterBindingIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    record Thing(Long id) {}

    interface NullRepository {
        Boolean domainWasNull(Thing thing);   // jsonb parameter
        Boolean scalarWasNull(Long id);       // bigint parameter
    }

    @BeforeAll
    static void deployFunctions() throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE FUNCTION null_repository_domain_was_null_v1(p jsonb)
                    RETURNS jsonb LANGUAGE sql AS $$ SELECT to_jsonb(p IS NULL) $$
                    """);
            stmt.execute("""
                    CREATE FUNCTION null_repository_scalar_was_null_v1(p bigint)
                    RETURNS jsonb LANGUAGE sql AS $$ SELECT to_jsonb(p IS NULL) $$
                    """);
        }
    }

    private NullRepository repo() {
        StubDataSource ds = new StubDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            }
        };
        return TietoClient.builder(ds).build().createRepository(NullRepository.class);
    }

    @Test
    void nullDomainObjectBindsAsSqlNull() {
        assertThat(repo().domainWasNull(null)).isTrue();
    }

    @Test
    void nullScalarBindsAsSqlNull() {
        assertThat(repo().scalarWasNull(null)).isTrue();
    }
}
