package net.unit8.tieto.generator.output;

import net.unit8.tieto.generator.ai.GeneratedFunction;
import net.unit8.tieto.generator.parser.GeneratorException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Deploys generated SQL functions directly to a PostgreSQL database.
 */
public class DirectDeployer {

    /**
     * Deploys all generated functions by executing CREATE OR REPLACE FUNCTION statements.
     *
     * @param conn the database connection
     * @param functions the generated functions to deploy
     */
    public void deploy(Connection conn, List<GeneratedFunction> functions) {
        try (Statement stmt = conn.createStatement()) {
            for (GeneratedFunction func : functions) {
                stmt.execute(func.sqlBody());
            }
        } catch (SQLException e) {
            throw new GeneratorException(
                    "Failed to deploy function: " + e.getMessage(), e);
        }
    }
}
