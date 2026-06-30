package net.unit8.tieto.generator.schema;

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

/**
 * {@link SchemaReader} reads tables/columns/keys, and — with no schema configured — reads the
 * connection's {@code current_schema()} rather than a hardcoded {@code public}, so it sees the
 * same schema the generator's existence checks and unqualified {@code CREATE} operate in.
 */
@Testcontainers(disabledWithoutDocker = true)
class SchemaReaderIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void schema() throws SQLException {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE customers (id bigint PRIMARY KEY, name text NOT NULL)");
            stmt.execute("CREATE TABLE orders (id bigint PRIMARY KEY, customer_id bigint REFERENCES customers(id))");
            stmt.execute("CREATE SCHEMA app");
            stmt.execute("CREATE TABLE app.widgets (id bigint PRIMARY KEY)");
        }
    }

    @Test
    void readsTablesColumnsAndKeysFromTheDefaultSchema() throws SQLException {
        try (Connection conn = newConnection()) {
            List<TableInfo> tables = new SchemaReader().readSchema(conn);

            assertThat(tables).extracting(TableInfo::name)
                    .contains("customers", "orders")
                    .doesNotContain("widgets"); // current_schema() is public, not app
            TableInfo orders = tables.stream().filter(t -> t.name().equals("orders")).findFirst().orElseThrow();
            assertThat(orders.primaryKeys()).containsExactly("id");
            assertThat(orders.foreignKeys()).extracting(ForeignKeyInfo::referencedTable).contains("customers");
        }
    }

    @Test
    void usesCurrentSchemaWhenSearchPathPointsElsewhere() throws SQLException {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO app");

            List<TableInfo> tables = new SchemaReader().readSchema(conn);

            // current_schema() is now "app", so only its table is read — not public's.
            assertThat(tables).extracting(TableInfo::name).containsExactly("widgets");
        }
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
