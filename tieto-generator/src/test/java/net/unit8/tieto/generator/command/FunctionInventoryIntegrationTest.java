package net.unit8.tieto.generator.command;

import net.unit8.tieto.generator.parser.MethodSpec;
import net.unit8.tieto.generator.parser.ParameterSpec;
import net.unit8.tieto.generator.parser.RepositorySpec;
import net.unit8.tieto.generator.parser.TypeDef;
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
 * Classifies deployed functions against a repository interface, and never crosses into a sibling
 * repository whose prefix overlaps.
 */
@Testcontainers(disabledWithoutDocker = true)
class FunctionInventoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Test
    void classifiesCurrentSupersededAndOrphanedWithoutCrossingSiblingRepositories() throws SQLException {
        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            // Current + one superseded version + an orphan + a spec method with its helper.
            create(stmt, "order_repository_find_by_id_v1", "p_id bigint");     // superseded (findById is v2)
            create(stmt, "order_repository_find_by_id_v2", "p_id bigint");     // current
            create(stmt, "order_repository_find_by_customer_id_v1", "p text"); // current
            create(stmt, "order_repository_save_v1", "p jsonb");              // current
            create(stmt, "order_repository_find_by_v1", "spec jsonb");        // current (spec)
            create(stmt, "order_repository_find_by_v1_spec_to_sql", "spec jsonb"); // current (helper)
            create(stmt, "order_repository_removed_v1", "p int");            // orphaned (no such method)
            // A sibling repository sharing the leading token — must be excluded by the prefix.
            create(stmt, "order_line_repository_save_v1", "p jsonb");
        }

        RepositorySpec repo = new RepositorySpec("com.example.OrderRepository", "OrderRepository", List.of(
                method("findById", 2),
                method("findByCustomerId", 1),
                method("save", 1),
                specMethod("findBy", 1)));

        FunctionInventory inventory;
        try (Connection conn = newConnection()) {
            inventory = FunctionInventory.of(conn, repo);
        }

        assertThat(names(inventory, FunctionInventory.Status.CURRENT)).containsExactlyInAnyOrder(
                "order_repository_find_by_id_v2",
                "order_repository_find_by_customer_id_v1",
                "order_repository_save_v1",
                "order_repository_find_by_v1",
                "order_repository_find_by_v1_spec_to_sql");
        assertThat(names(inventory, FunctionInventory.Status.SUPERSEDED))
                .containsExactly("order_repository_find_by_id_v1");
        assertThat(names(inventory, FunctionInventory.Status.ORPHANED))
                .containsExactly("order_repository_removed_v1");
        assertThat(inventory.entries()).extracting(FunctionInventory.Entry::functionName)
                .as("a sibling repository's functions are not in scope")
                .doesNotContain("order_line_repository_save_v1");
    }

    private static List<String> names(FunctionInventory inv, FunctionInventory.Status status) {
        return inv.byStatus(status).stream().map(FunctionInventory.Entry::functionName).toList();
    }

    private static MethodSpec method(String name, int version) {
        return new MethodSpec(name, "void", List.of(), "", version);
    }

    private static MethodSpec specMethod(String name, int version) {
        TypeDef sealedSpec = new TypeDef("OrderSpec", "com.example.OrderSpec", null, true, "",
                List.of(), List.of());
        return new MethodSpec(name, "java.util.List", List.of(new ParameterSpec("spec", "OrderSpec", sealedSpec)),
                "", version);
    }

    private static void create(Statement stmt, String name, String args) throws SQLException {
        stmt.execute("CREATE OR REPLACE FUNCTION " + name + "(" + args
                + ") RETURNS int LANGUAGE sql AS $$ SELECT 1 $$");
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
