package net.unit8.tieto.generator.output;

import net.unit8.tieto.generator.parser.ComponentDef;
import net.unit8.tieto.generator.parser.GeneratorException;
import net.unit8.tieto.generator.parser.TypeDef;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the behavioral injection probe: a safe (value-binding) spec function
 * passes; an unsafe (value-concatenating) one is caught regardless of how it
 * extracts the value.
 */
@Testcontainers(disabledWithoutDocker = true)
class SpecInjectionProbeIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private final SpecInjectionProbe probe = new SpecInjectionProbe();

    @BeforeAll
    static void schema() throws SQLException {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE orders (id bigint PRIMARY KEY, customer_id text)");
            stmt.execute("INSERT INTO orders VALUES (1, 'CUST-001')");
        }
    }

    @Test
    void probeSpecsForProbesEveryStringLeafWrappedInAnAnd() {
        TypeDef spec = new TypeDef("OrderSpec", "OrderSpec", null, true, "", List.of(), List.of(
                new TypeDef("And", "And", "and", false, "", List.of(), List.of()),
                new TypeDef("HighValue", "HighValue", "highValue", false, "",
                        List.of(new ComponentDef("min", "BigDecimal")), List.of()),
                new TypeDef("ForCustomer", "ForCustomer", "forCustomer", false, "",
                        List.of(new ComponentDef("customerId", "String")), List.of()),
                new TypeDef("ByName", "ByName", "byName", false, "",
                        List.of(new ComponentDef("name", "String")), List.of())));

        // One probe per String leaf (customerId, name); numeric 'min' is not probed;
        // each is wrapped in an 'and' so the path-threading runs.
        assertThat(SpecInjectionProbe.probeSpecsFor(spec)).containsExactlyInAnyOrder(
                "{\"kind\":\"and\",\"specs\":[{\"kind\":\"forCustomer\",\"customerId\":\"'\"}]}",
                "{\"kind\":\"and\",\"specs\":[{\"kind\":\"byName\",\"name\":\"'\"}]}");
    }

    @Test
    void passesAFunctionThatBindsTheValue() throws SQLException {
        deploySafe("safe_find");
        try (Connection conn = newConnection()) {
            assertThatCode(() -> probe.verify(conn, "safe_find",
                    "{\"kind\":\"forCustomer\",\"customerId\":\"'\"}"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void catchesAFunctionThatConcatenatesTheValue() throws SQLException {
        deployUnsafe("unsafe_find");
        try (Connection conn = newConnection()) {
            assertThatThrownBy(() -> probe.verify(conn, "unsafe_find",
                    "{\"kind\":\"forCustomer\",\"customerId\":\"'\"}"))
                    .isInstanceOf(GeneratorException.class)
                    .hasMessageContaining("Injection probe failed");
        }
    }

    /** A function that binds the value via $1 #>> path — safe. */
    private static void deploySafe(String name) throws SQLException {
        execute("""
                CREATE FUNCTION %s(spec jsonb) RETURNS SETOF jsonb LANGUAGE plpgsql AS $$
                BEGIN
                  RETURN QUERY EXECUTE
                    'SELECT to_jsonb(o) FROM orders o WHERE customer_id = ($1 #>> ''{customerId}'')'
                    USING spec;
                END; $$
                """.formatted(name));
    }

    /** A function that concatenates the value into the SQL text — injectable. */
    private static void deployUnsafe(String name) throws SQLException {
        execute("""
                CREATE FUNCTION %s(spec jsonb) RETURNS SETOF jsonb LANGUAGE plpgsql AS $$
                BEGIN
                  RETURN QUERY EXECUTE
                    'SELECT to_jsonb(o) FROM orders o WHERE customer_id = ''' || (spec->>'customerId') || '''';
                END; $$
                """.formatted(name));
    }

    private static void execute(String sql) throws SQLException {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
