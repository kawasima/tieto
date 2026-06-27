package net.unit8.tieto.generator.output;

import net.unit8.tieto.generator.parser.GeneratorException;
import net.unit8.tieto.generator.parser.MethodSpec;
import net.unit8.tieto.generator.parser.ParameterSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link ReadSmokeVerifier} passes a function whose body resolves
 * against the schema (even with zero matching rows) and fails one whose body
 * references a non-existent column — the class of error that survives CREATE and
 * the static validator and only surfaces on first call.
 */
@Testcontainers
class ReadSmokeVerifierIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private final ReadSmokeVerifier verifier = new ReadSmokeVerifier();

    @BeforeAll
    static void schema() throws SQLException {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE orders (id bigint PRIMARY KEY, customer_id text)");
            stmt.execute("INSERT INTO orders VALUES (1, 'CUST-001')");
        }
    }

    @Test
    void passesAFunctionThatResolvesEvenWithZeroRows() throws SQLException {
        try (Connection conn = newConnection()) {
            create(conn, """
                    find_by_id_v1(id bigint) RETURNS jsonb AS $$
                      SELECT to_jsonb(o) FROM orders o WHERE o.id = id
                    $$ LANGUAGE sql""");
            // id 0 matches nothing, but the body still plans and runs -> pass.
            assertThatCode(() -> verifier.verify(conn, "find_by_id_v1",
                    method("findById", "Optional<Order>", simple("id", "Long"))))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void failsAFunctionReferencingANonexistentColumn() throws SQLException {
        try (Connection conn = newConnection()) {
            // plpgsql (unlike a LANGUAGE sql function) defers column resolution to first
            // call, so this CREATE succeeds and the broken reference surfaces only when run
            // — exactly the case the read smoke exists to catch.
            create(conn, """
                    find_broken_v1(id bigint) RETURNS SETOF jsonb LANGUAGE plpgsql AS $$
                    BEGIN
                      RETURN QUERY SELECT to_jsonb(o) FROM orders o WHERE o.totl = id;
                    END $$""");
            assertThatThrownBy(() -> verifier.verify(conn, "find_broken_v1",
                    method("findBroken", "List<Order>", simple("id", "Long"))))
                    .isInstanceOf(GeneratorException.class)
                    .hasMessageContaining("Read smoke failed");
        }
    }

    @Test
    void passesASingleObjectReadUsingIntoStrictWithNoMatchingRow() throws SQLException {
        try (Connection conn = newConnection()) {
            // A correct findById may use SELECT ... INTO STRICT; with the synthesized id 0
            // it finds no row and raises NO_DATA_FOUND (P0002). That is the probe value not
            // matching, not a broken body, so the smoke must pass.
            create(conn, """
                    find_strict_v1(p_id bigint) RETURNS jsonb LANGUAGE plpgsql AS $$
                    DECLARE result jsonb;
                    BEGIN
                      SELECT to_jsonb(o) INTO STRICT result FROM orders o WHERE o.id = p_id;
                      RETURN result;
                    END $$""");
            assertThatCode(() -> verifier.verify(conn, "find_strict_v1",
                    method("findById", "Optional<Order>", simple("id", "Long"))))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void passesWhenTheSynthesizedStringIsNotAValidEnumValue() throws SQLException {
        try (Connection conn = newConnection()) {
            try (java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TYPE IF EXISTS mood CASCADE");
                stmt.execute("CREATE TYPE mood AS ENUM ('happy', 'sad')");
            }
            // A correct finder casts its String arg to an enum column; the synthesized
            // 'tieto-smoke' is not a valid enum member -> invalid_text_representation (22P02).
            // That is the probe value, not a body bug, so the smoke must pass.
            create(conn, """
                    find_by_mood_v1(m text) RETURNS SETOF jsonb LANGUAGE plpgsql AS $$
                    BEGIN
                      RETURN QUERY SELECT to_jsonb(o) FROM orders o WHERE m::mood = 'happy';
                    END $$""");
            assertThatCode(() -> verifier.verify(conn, "find_by_mood_v1",
                    method("findByMood", "List<Order>", simple("mood", "String"))))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void passesANoArgFinder() throws SQLException {
        try (Connection conn = newConnection()) {
            create(conn, """
                    find_all_v1() RETURNS SETOF jsonb AS $$
                      SELECT to_jsonb(o) FROM orders o
                    $$ LANGUAGE sql""");
            assertThatCode(() -> verifier.verify(conn, "find_all_v1",
                    method("findAll", "List<Order>")))
                    .doesNotThrowAnyException();
        }
    }

    private static MethodSpec method(String name, String returnType, ParameterSpec... params) {
        return new MethodSpec(name, returnType, List.of(params), "", 1);
    }

    private static ParameterSpec simple(String name, String type) {
        return new ParameterSpec(name, type, null);
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
