package net.unit8.tieto.generator.output;

import org.junit.jupiter.api.BeforeAll;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirically proves that the parameterized spec contract is injection-safe: a
 * representative main + {@code _spec_to_sql} helper (built exactly as the prompt
 * instructs — values referenced via {@code $1 #>> path}, bound with
 * {@code EXECUTE ... USING spec}) treats a malicious spec value as a literal,
 * not as SQL, so it cannot break out of the {@code WHERE} clause.
 */
@Testcontainers(disabledWithoutDocker = true)
class SpecInjectionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void deploy() throws SQLException {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE orders (id bigint PRIMARY KEY, customer_id text, amount numeric)");
            stmt.execute("INSERT INTO orders VALUES (1, 'CUST-001', 100), (2, 'CUST-002', 500)");
            stmt.execute("""
                    CREATE FUNCTION find_by_spec_to_sql(node jsonb, path text[]) RETURNS text
                    LANGUAGE plpgsql AS $$
                    DECLARE k text := node->>'kind'; parts text[] := '{}'; child jsonb; i int := 0;
                    BEGIN
                      IF node IS NULL THEN RETURN 'TRUE'; END IF;
                      IF k = 'and' THEN
                        FOR child IN SELECT jsonb_array_elements(node->'specs') LOOP
                          parts := parts || find_by_spec_to_sql(child, path || ARRAY['specs', i::text]);
                          i := i + 1;
                        END LOOP;
                        IF array_length(parts,1) IS NULL THEN RETURN 'TRUE'; END IF;
                        RETURN '(' || array_to_string(parts, ' AND ') || ')';
                      ELSIF k = 'not' THEN
                        RETURN 'NOT (' || find_by_spec_to_sql(node->'spec', path || ARRAY['spec']) || ')';
                      ELSIF k = 'forCustomer' THEN
                        RETURN format('customer_id = ($1 #>> %L)', path || ARRAY['customerId']);
                      ELSIF k = 'highValue' THEN
                        RETURN format('amount >= ($1 #>> %L)::numeric', path || ARRAY['min']);
                      ELSE RAISE EXCEPTION 'unknown spec kind: %', k; END IF;
                    END; $$
                    """);
            stmt.execute("""
                    CREATE FUNCTION find_by(spec jsonb) RETURNS SETOF bigint
                    LANGUAGE plpgsql AS $$
                    BEGIN
                      RETURN QUERY EXECUTE
                        'SELECT id FROM orders o WHERE ' || find_by_spec_to_sql(spec, '{}'::text[])
                        USING spec;
                    END; $$
                    """);
        }
    }

    @Test
    void aMatchingValueReturnsTheRow() throws SQLException {
        assertThat(findBy("{\"kind\":\"forCustomer\",\"customerId\":\"CUST-001\"}"))
                .containsExactly(1L);
    }

    @Test
    void aMaliciousValueCannotBreakOutOfTheWhereClause() throws SQLException {
        // If the value were concatenated, "x' OR '1'='1" would match every row.
        // Bound via $1, it is just a literal that matches no customer.
        assertThat(findBy("{\"kind\":\"forCustomer\",\"customerId\":\"x' OR '1'='1\"}"))
                .isEmpty();
    }

    @Test
    void nestedAndComposesCorrectly() throws SQLException {
        assertThat(findBy("""
                {"kind":"and","specs":[{"kind":"forCustomer","customerId":"CUST-002"}]}"""))
                .containsExactly(2L);
    }

    @Test
    void numericLeafBindsAndComparesCorrectly() throws SQLException {
        assertThat(findBy("{\"kind\":\"highValue\",\"min\":200}")).containsExactly(2L);
    }

    @Test
    void notAndMultiChildAndComposeWithPathThreading() throws SQLException {
        assertThat(findBy("""
                {"kind":"not","spec":{"kind":"forCustomer","customerId":"CUST-001"}}"""))
                .containsExactly(2L);
        assertThat(findBy("""
                {"kind":"and","specs":[
                  {"kind":"forCustomer","customerId":"CUST-002"},
                  {"kind":"highValue","min":200}]}"""))
                .containsExactly(2L);
    }

    @Test
    void aMaliciousValueNestedDeepInTheTreeIsStillBound() throws SQLException {
        assertThat(findBy("""
                {"kind":"and","specs":[
                  {"kind":"forCustomer","customerId":"x' OR '1'='1"}]}"""))
                .isEmpty();
    }

    private static java.util.List<Long> findBy(String specJson) throws SQLException {
        var ids = new java.util.ArrayList<Long>();
        try (Connection conn = newConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT find_by(?::jsonb)")) {
            ps.setString(1, specJson);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
