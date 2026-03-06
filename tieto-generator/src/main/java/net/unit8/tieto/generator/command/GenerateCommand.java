package net.unit8.tieto.generator.command;

import net.unit8.tieto.generator.ai.AiProvider;
import net.unit8.tieto.generator.ai.AiProviderFactory;
import net.unit8.tieto.generator.ai.GeneratedFunction;
import net.unit8.tieto.generator.ai.PromptBuilder;
import net.unit8.tieto.generator.parser.GeneratorException;
import net.unit8.tieto.generator.output.DirectDeployer;
import net.unit8.tieto.generator.output.SqlFileWriter;
import net.unit8.tieto.generator.parser.MethodSpec;
import net.unit8.tieto.generator.parser.RepositoryParser;
import net.unit8.tieto.generator.parser.RepositorySpec;
import net.unit8.tieto.generator.schema.SchemaReader;
import net.unit8.tieto.generator.schema.TableInfo;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command that generates PostgreSQL functions from a Repository interface.
 */
@Command(name = "generate",
        description = "Generate PostgreSQL Functions from a Repository interface")
public class GenerateCommand implements Callable<Integer> {

    @Option(names = "--source-dir", required = true,
            description = "Root directory of Java sources")
    private Path sourceDir;

    @Option(names = "--repository", required = true,
            description = "Fully qualified Repository interface name")
    private String repositoryClassName;

    @Option(names = "--db-url", required = true,
            description = "JDBC URL for the target database")
    private String dbUrl;

    @Option(names = "--db-user", required = true,
            description = "Database username")
    private String dbUser;

    @Option(names = "--db-password", required = true,
            description = "Database password")
    private String dbPassword;

    @Option(names = "--ai-provider",
            description = "AI provider: claude, openai, claude-cli")
    private String aiProvider;

    @Option(names = "--ai-api-key",
            description = "API key for the AI provider")
    private String aiApiKey;

    @Option(names = "--ai-model",
            description = "AI model override")
    private String aiModel;

    @Option(names = "--ai-command",
            description = "Custom CLI command for AI generation (e.g. \"ollama run codellama\")")
    private String aiCommand;

    @Option(names = "--output-dir", defaultValue = "sql/",
            description = "Output directory for generated SQL files")
    private Path outputDir;

    @Option(names = "--output-mode", defaultValue = "deploy",
            description = "Output mode: deploy (default) or file")
    private String outputMode;

    @Option(names = "--force",
            description = "Force regeneration even if the function version already exists")
    private boolean force;

    @Override
    public Integer call() throws Exception {
        System.out.println("Parsing repository: " + repositoryClassName);

        // 1. Parse Repository interface + Javadoc
        RepositorySpec repoSpec = new RepositoryParser().parse(sourceDir, repositoryClassName);
        System.out.println("Found " + repoSpec.methods().size() + " methods");

        // 2. Read DB schema
        List<TableInfo> schema;
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            schema = new SchemaReader().readSchema(conn);
        }
        System.out.println("Read " + schema.size() + " tables from database");

        // 3. For each method, generate a PostgreSQL Function via AI
        AiProvider ai = createAiProvider();
        PromptBuilder promptBuilder = new PromptBuilder();

        List<GeneratedFunction> functions = new ArrayList<>();
        for (MethodSpec method : repoSpec.methods()) {
            String versionedName = resolveFunctionName(repoSpec, method);

            if (!force && functionExists(versionedName, repoSpec.simpleName())) {
                System.out.println("Skipping " + versionedName + " (already exists)");
                continue;
            }

            System.out.println("Generating function for: " + method.name() + " (v" + method.version() + ")...");
            String prompt = promptBuilder.build(repoSpec, method, schema);
            GeneratedFunction generated = ai.generateFunction(prompt);
            functions.add(generated);
            System.out.println("  -> " + generated.functionName());
        }

        if (functions.isEmpty()) {
            System.out.println("No functions to generate (all versions up to date)");
            return 0;
        }

        // 4. Output
        if ("deploy".equals(outputMode)) {
            try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
                new DirectDeployer().deploy(conn, functions);
            }
            System.out.println("Deployed " + functions.size() + " functions to database");
        } else {
            new SqlFileWriter().write(outputDir, repoSpec.simpleName(), functions);
            System.out.println("Wrote SQL files to " + outputDir);
        }

        return 0;
    }

    private static String resolveFunctionName(RepositorySpec repo, MethodSpec method) {
        return camelToSnake(repo.simpleName()) + "_" + camelToSnake(method.name())
                + "_v" + method.version();
    }

    private boolean functionExists(String functionName, String repositoryName) {
        if ("deploy".equals(outputMode)) {
            return functionExistsInDb(functionName);
        } else {
            return functionExistsInFile(functionName, repositoryName);
        }
    }

    private boolean functionExistsInDb(String functionName) {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM information_schema.routines WHERE routine_name = ?")) {
            ps.setString(1, functionName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean functionExistsInFile(String functionName, String repositoryName) {
        Path outputFile = outputDir.resolve(camelToSnake(repositoryName) + ".sql");
        if (!Files.exists(outputFile)) {
            return false;
        }
        try {
            String content = Files.readString(outputFile);
            return content.contains(functionName);
        } catch (IOException e) {
            return false;
        }
    }

    private static String camelToSnake(String camel) {
        return camel
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase();
    }

    private AiProvider createAiProvider() {
        if (aiCommand != null) {
            return AiProviderFactory.createFromCommand(aiCommand);
        }
        if (aiProvider == null) {
            throw new GeneratorException(
                    "Either --ai-provider or --ai-command must be specified");
        }
        if (aiApiKey == null && !aiProvider.equalsIgnoreCase("claude-cli")) {
            throw new GeneratorException(
                    "--ai-api-key is required for provider: " + aiProvider);
        }
        return AiProviderFactory.create(aiProvider, aiApiKey, aiModel);
    }
}
