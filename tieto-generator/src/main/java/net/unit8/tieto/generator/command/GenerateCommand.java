package net.unit8.tieto.generator.command;

import net.unit8.tieto.generator.ai.AiProvider;
import net.unit8.tieto.generator.ai.AiProviderFactory;
import net.unit8.tieto.generator.ai.GeneratedFunction;
import net.unit8.tieto.generator.ai.PromptBuilder;
import net.unit8.tieto.generator.output.DirectDeployer;
import net.unit8.tieto.generator.output.SqlFileWriter;
import net.unit8.tieto.generator.parser.MethodSpec;
import net.unit8.tieto.generator.parser.RepositoryParser;
import net.unit8.tieto.generator.parser.RepositorySpec;
import net.unit8.tieto.generator.schema.SchemaReader;
import net.unit8.tieto.generator.schema.TableInfo;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
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

    @Option(names = "--ai-provider", required = true,
            description = "AI provider: claude, openai")
    private String aiProvider;

    @Option(names = "--ai-api-key", required = true,
            description = "API key for the AI provider")
    private String aiApiKey;

    @Option(names = "--ai-model",
            description = "AI model override")
    private String aiModel;

    @Option(names = "--output-dir", defaultValue = "sql/",
            description = "Output directory for generated SQL files")
    private Path outputDir;

    @Option(names = "--output-mode", defaultValue = "file",
            description = "Output mode: file or deploy")
    private String outputMode;

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
        AiProvider ai = AiProviderFactory.create(aiProvider, aiApiKey, aiModel);
        PromptBuilder promptBuilder = new PromptBuilder();

        List<GeneratedFunction> functions = new ArrayList<>();
        for (MethodSpec method : repoSpec.methods()) {
            System.out.println("Generating function for: " + method.name() + "...");
            String prompt = promptBuilder.build(repoSpec, method, schema);
            GeneratedFunction generated = ai.generateFunction(prompt);
            functions.add(generated);
            System.out.println("  -> " + generated.functionName());
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
}
