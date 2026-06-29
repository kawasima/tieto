package net.unit8.tieto.generator.output;

import net.unit8.tieto.generator.output.SignatureVerifier.ExpectedSignature;
import net.unit8.tieto.generator.parser.GeneratorException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link SignatureVerifier} accepts a function whose declared
 * SETOF-ness, void-ness, and arity match the Java method, and rejects those
 * mismatches — while NOT rejecting a non-void method that returns a native
 * scalar (which is allowed) or an enum/domain argument (whose binding is not
 * asserted here).
 *
 * <p>The container is shared across tests, so each test uses a distinct function
 * name: same-name-different-signature would create a PostgreSQL overload rather
 * than replace, perturbing other tests.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class SignatureVerifierIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private final SignatureVerifier verifier = new SignatureVerifier();

    @Test
    void acceptsAMatchingSingleJsonbFunction() throws SQLException {
        try (Connection conn = newConnection()) {
            create(conn, "sig_single_ok_v1(id bigint) RETURNS jsonb AS $$ SELECT '{}'::jsonb $$ LANGUAGE sql");
            assertThatCode(() -> verifier.verify(conn, "sig_single_ok_v1",
                    new ExpectedSignature(false, false, 1)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void acceptsAMatchingSetofFunction() throws SQLException {
        try (Connection conn = newConnection()) {
            create(conn, "sig_setof_ok_v1() RETURNS SETOF jsonb AS $$ SELECT '{}'::jsonb $$ LANGUAGE sql");
            assertThatCode(() -> verifier.verify(conn, "sig_setof_ok_v1",
                    new ExpectedSignature(true, false, 0)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void acceptsAMatchingVoidFunctionWithAJsonbArg() throws SQLException {
        try (Connection conn = newConnection()) {
            create(conn, "sig_void_ok_v1(arg jsonb) RETURNS void AS $$ BEGIN END $$ LANGUAGE plpgsql");
            assertThatCode(() -> verifier.verify(conn, "sig_void_ok_v1",
                    new ExpectedSignature(false, true, 1)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void acceptsANonVoidScalarReturn() {
        // A scalar method (e.g. long count()) may return a native type, not jsonb.
        try (Connection conn = newConnection()) {
            create(conn, "sig_scalar_ok_v1() RETURNS bigint AS $$ SELECT 0::bigint $$ LANGUAGE sql");
            assertThatCode(() -> verifier.verify(conn, "sig_scalar_ok_v1",
                    new ExpectedSignature(false, false, 0)))
                    .doesNotThrowAnyException();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void rejectsSetofWhenSingleExpected() throws SQLException {
        try (Connection conn = newConnection()) {
            create(conn, "sig_setof_bad_v1(id bigint) RETURNS SETOF jsonb AS $$ SELECT '{}'::jsonb $$ LANGUAGE sql");
            assertThatThrownBy(() -> verifier.verify(conn, "sig_setof_bad_v1",
                    new ExpectedSignature(false, false, 1)))
                    .isInstanceOf(GeneratorException.class)
                    .hasMessageContaining("SETOF");
        }
    }

    @Test
    void rejectsVoidWhenAValueIsExpected() throws SQLException {
        try (Connection conn = newConnection()) {
            create(conn, "sig_void_bad_v1(id bigint) RETURNS void AS $$ BEGIN END $$ LANGUAGE plpgsql");
            assertThatThrownBy(() -> verifier.verify(conn, "sig_void_bad_v1",
                    new ExpectedSignature(false, false, 1)))
                    .isInstanceOf(GeneratorException.class)
                    .hasMessageContaining("VOID");
        }
    }

    @Test
    void rejectsAValueReturnWhenVoidExpected() throws SQLException {
        try (Connection conn = newConnection()) {
            create(conn, "sig_value_bad_v1(arg jsonb) RETURNS jsonb AS $$ SELECT arg $$ LANGUAGE sql");
            assertThatThrownBy(() -> verifier.verify(conn, "sig_value_bad_v1",
                    new ExpectedSignature(false, true, 1)))
                    .isInstanceOf(GeneratorException.class)
                    .hasMessageContaining("VOID");
        }
    }

    @Test
    void rejectsWrongArity() throws SQLException {
        try (Connection conn = newConnection()) {
            create(conn, "sig_arity_bad_v1() RETURNS jsonb AS $$ SELECT '{}'::jsonb $$ LANGUAGE sql");
            assertThatThrownBy(() -> verifier.verify(conn, "sig_arity_bad_v1",
                    new ExpectedSignature(false, false, 1)))
                    .isInstanceOf(GeneratorException.class)
                    .hasMessageContaining("argument");
        }
    }

    private static void create(Connection conn, String fnTail) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE OR REPLACE FUNCTION " + fnTail);
        }
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
