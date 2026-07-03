package net.unit8.tieto.generator.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end {@code functions prune}: dry-run changes nothing, {@code --yes} drops only superseded
 * versions by default, {@code --keep-last} retains the newest, and {@code --include-orphaned} adds
 * orphans — never touching current functions.
 */
@Testcontainers(disabledWithoutDocker = true)
class FunctionsPruneIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @TempDir
    Path sourceDir;

    @BeforeEach
    void writeInterfaceAndDeploy() throws IOException, SQLException {
        // findById is at v3 (so v1 and v2 are superseded); save is at v1 (current).
        Path file = sourceDir.resolve("com/example/OrderRepository.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package com.example;
                import net.unit8.tieto.core.annotation.FunctionVersion;
                import java.util.Optional;
                public interface OrderRepository {
                    @FunctionVersion(3) Optional<String> findById(Long id);
                    @FunctionVersion(1) void save(String data);
                }
                """);

        try (Connection conn = newConnection(); Statement stmt = conn.createStatement()) {
            for (String name : new String[]{
                    "order_repository_find_by_id_v1",   // superseded
                    "order_repository_find_by_id_v2",   // superseded
                    "order_repository_find_by_id_v3",   // current
                    "order_repository_save_v1",         // current
                    "order_repository_removed_v1"}) {   // orphaned
                stmt.execute("CREATE OR REPLACE FUNCTION " + name
                        + "(p int) RETURNS int LANGUAGE sql AS $$ SELECT 1 $$");
            }
        }
    }

    @Test
    void dryRunDropsNothing() throws SQLException {
        run();   // no --yes
        assertThat(exists("order_repository_find_by_id_v1")).isTrue();
        assertThat(exists("order_repository_find_by_id_v2")).isTrue();
    }

    @Test
    void yesDropsSupersededButKeepsCurrentAndOrphaned() throws SQLException {
        run("--yes");
        assertThat(exists("order_repository_find_by_id_v1")).as("superseded dropped").isFalse();
        assertThat(exists("order_repository_find_by_id_v2")).as("superseded dropped").isFalse();
        assertThat(exists("order_repository_find_by_id_v3")).as("current kept").isTrue();
        assertThat(exists("order_repository_save_v1")).as("current kept").isTrue();
        assertThat(exists("order_repository_removed_v1")).as("orphaned kept without the flag").isTrue();
    }

    @Test
    void keepLastRetainsTheNewestSupersededVersion() throws SQLException {
        run("--yes", "--keep-last", "1");
        assertThat(exists("order_repository_find_by_id_v1")).as("older superseded dropped").isFalse();
        assertThat(exists("order_repository_find_by_id_v2")).as("newest superseded kept").isTrue();
        assertThat(exists("order_repository_find_by_id_v3")).as("current kept").isTrue();
    }

    @Test
    void includeOrphanedAlsoDropsOrphans() throws SQLException {
        run("--yes", "--include-orphaned");
        assertThat(exists("order_repository_removed_v1")).as("orphaned dropped").isFalse();
        assertThat(exists("order_repository_find_by_id_v3")).as("current still kept").isTrue();
    }

    private void run(String... extra) {
        String[] base = {
                "--source-dir", sourceDir.toString(),
                "--repository", "com.example.OrderRepository",
                "--db-url", POSTGRES.getJdbcUrl(),
                "--db-user", POSTGRES.getUsername(),
                "--db-password", POSTGRES.getPassword()};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        int code = new CommandLine(new FunctionsPruneCommand()).execute(args);
        assertThat(code).isZero();
    }

    private static boolean exists(String name) throws SQLException {
        try (Connection conn = newConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT 1 FROM information_schema.routines"
                                + " WHERE routine_schema = current_schema() AND routine_name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
