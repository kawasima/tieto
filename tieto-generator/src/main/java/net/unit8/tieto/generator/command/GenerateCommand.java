package net.unit8.tieto.generator.command;

import net.unit8.tieto.generator.ai.AiProvider;
import net.unit8.tieto.generator.ai.AiProviderFactory;
import net.unit8.tieto.generator.ai.GeneratedFunction;
import net.unit8.tieto.generator.ai.GeneratedSqlValidator;
import net.unit8.tieto.generator.ai.PromptBuilder;
import net.unit8.tieto.generator.parser.GeneratorException;
import net.unit8.tieto.generator.output.DirectDeployer;
import net.unit8.tieto.generator.output.SpecInjectionProbe;
import net.unit8.tieto.generator.output.SqlFileWriter;
import net.unit8.tieto.generator.parser.MethodSpec;
import net.unit8.tieto.generator.parser.ParameterSpec;
import net.unit8.tieto.generator.parser.RepositoryParser;
import net.unit8.tieto.generator.parser.RepositorySpec;
import net.unit8.tieto.generator.parser.TypeDef;
import net.unit8.tieto.generator.schema.SchemaReader;
import net.unit8.tieto.generator.schema.TableInfo;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
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

    @Option(names = "--db-password", interactive = true, arity = "0..1",
            description = "Database password. Prefer the TIETO_DB_PASSWORD environment variable;"
                    + " pass the flag with no value to be prompted (no echo). Passing the value on"
                    + " the command line is insecure on shared hosts.")
    private String dbPassword;

    @Option(names = "--ai-provider",
            description = "AI provider: claude, openai, claude-cli")
    private String aiProvider;

    @Option(names = "--ai-api-key", interactive = true, arity = "0..1",
            description = "API key for the AI provider. Prefer the TIETO_AI_API_KEY environment"
                    + " variable; pass the flag with no value to be prompted (no echo).")
    private String aiApiKey;

    @Option(names = "--ai-model",
            description = "AI model override. Targets standard chat models (e.g. gpt-4o, "
                    + "claude-sonnet-4); OpenAI reasoning models (o1/o3) are not supported "
                    + "because they reject max_tokens and a non-default temperature.")
    private String aiModel;

    @Option(names = "--ai-command",
            description = "Custom CLI command for AI generation (e.g. \"ollama run codellama\")")
    private String aiCommand;

    @Option(names = "--ai-max-tokens", defaultValue = "8192",
            description = "Max output tokens for API providers (claude, openai). Raise this if a"
                    + " large function is rejected as truncated. Default: ${DEFAULT-VALUE}")
    private int aiMaxTokens;

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
        // Resolve secrets from the command line or, preferably, the environment, so
        // they need not appear in the process list or shell history. (An empty string
        // is a valid password, e.g. for trust/peer auth; only an absent one is an error.)
        dbPassword = SecretOption.resolve(dbPassword, System.getenv("TIETO_DB_PASSWORD"));
        if (dbPassword == null) {
            System.err.println("Database password is required: set the TIETO_DB_PASSWORD"
                    + " environment variable, or pass --db-password (you will be prompted).");
            return 2;
        }
        // The API key may legitimately come from a file via $(...); strip a stray newline.
        aiApiKey = SecretOption.resolve(aiApiKey, System.getenv("TIETO_AI_API_KEY"));
        if (aiApiKey != null) {
            aiApiKey = aiApiKey.strip();
        }

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
        GeneratedSqlValidator validator = new GeneratedSqlValidator();

        List<GeneratedFunction> functions = new ArrayList<>();
        List<MethodSpec> deployedSpecMethods = new ArrayList<>();
        for (MethodSpec method : repoSpec.methods()) {
            String versionedName = resolveFunctionName(repoSpec, method);

            if (!force && functionExists(versionedName, repoSpec.simpleName())) {
                System.out.println("Skipping " + versionedName + " (already exists)");
                continue;
            }

            System.out.println("Generating function for: " + method.name() + " (v" + method.version() + ")...");
            String prompt = promptBuilder.build(repoSpec, method, schema);
            GeneratedFunction generated = ai.generateFunction(prompt);
            // Never deploy or write unvalidated AI output: only the expected
            // CREATE OR REPLACE FUNCTION is allowed, plus the _spec_to_sql helper
            // when (and only when) the method takes a composable Specification.
            validator.validate(generated.sqlBody(), versionedName, hasSpecParameter(method));
            functions.add(generated);
            if (hasSpecParameter(method)) {
                deployedSpecMethods.add(method);
            }
            System.out.println("  -> " + generated.functionName());
        }

        if (functions.isEmpty()) {
            System.out.println("No functions to generate (all versions up to date)");
            return 0;
        }

        // 4. Output
        if ("deploy".equals(outputMode)) {
            // Deploy and behaviorally probe each spec function in one transaction:
            // commit only if every probe proves leaf values are bound, not concatenated.
            List<DirectDeployer.DeployVerification> probes = injectionProbes(repoSpec, deployedSpecMethods);
            try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
                new DirectDeployer().deploy(conn, functions, probes);
            }
            System.out.println("Deployed " + functions.size() + " functions to database");
        } else {
            // Non-force runs skip functions already in the file, so append the new ones
            // to preserve them; --force regenerates everything, so rewrite the whole file.
            new SqlFileWriter().write(outputDir, repoSpec.simpleName(), functions, force);
            System.out.println("Wrote SQL files to " + outputDir);
        }

        return 0;
    }

    /**
     * Builds the behavioral injection probes for each freshly deployed spec function:
     * one per String-typed leaf field. Run inside the deploy transaction, a failing
     * probe rolls back the deploy.
     */
    private List<DirectDeployer.DeployVerification> injectionProbes(
            RepositorySpec repoSpec, List<MethodSpec> specMethods) {
        SpecInjectionProbe probe = new SpecInjectionProbe();
        List<DirectDeployer.DeployVerification> probes = new ArrayList<>();
        for (MethodSpec method : specMethods) {
            String functionName = resolveFunctionName(repoSpec, method);
            List<String> probeSpecs = SpecInjectionProbe.probeSpecsFor(specTypeOf(method));
            if (probeSpecs.isEmpty()) {
                System.out.println("  (no string-typed leaf to probe " + functionName
                        + "; relying on the static contract check)");
                continue;
            }
            for (String probeSpec : probeSpecs) {
                probes.add(conn -> probe.verify(conn, functionName, probeSpec));
            }
        }
        return probes;
    }

    // The same predicate as hasSpecParameter, so a method in deployedSpecMethods
    // always has a sealed spec parameter and orElseThrow cannot fire.
    private static TypeDef specTypeOf(MethodSpec method) {
        return method.parameters().stream()
                .map(ParameterSpec::typeDef)
                .filter(t -> t != null && t.sealed())
                .findFirst()
                .orElseThrow();
    }

    private static String resolveFunctionName(RepositorySpec repo, MethodSpec method) {
        return camelToSnake(repo.simpleName()) + "_" + camelToSnake(method.name())
                + "_v" + method.version();
    }

    /**
     * Whether the method takes a composable Specification (a sealed type), in
     * which case the generator also emits a {@code _spec_to_sql} helper. Mirrors
     * the condition in {@code PromptBuilder.specRules}.
     */
    private static boolean hasSpecParameter(MethodSpec method) {
        return method.parameters().stream()
                .anyMatch(p -> p.typeDef() != null && p.typeDef().sealed());
    }

    private boolean functionExists(String functionName, String repositoryName) {
        if ("deploy".equals(outputMode)) {
            return functionExistsInDb(functionName);
        } else {
            return functionExistsInFile(functionName, repositoryName);
        }
    }

    private boolean functionExistsInDb(String functionName) {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            return DeployedFunctions.existsInDatabase(conn, functionName);
        } catch (SQLException e) {
            throw new GeneratorException(
                    "Failed to connect to check whether function " + functionName
                            + " already exists: " + e.getMessage(), e);
        }
    }

    private boolean functionExistsInFile(String functionName, String repositoryName) {
        Path outputFile = outputDir.resolve(camelToSnake(repositoryName) + ".sql");
        if (!Files.exists(outputFile)) {
            return false;
        }
        try {
            return DeployedFunctions.declaredInFile(Files.readString(outputFile), functionName);
        } catch (IOException e) {
            throw new GeneratorException(
                    "Failed to read " + outputFile + " to check for an existing function: "
                            + e.getMessage(), e);
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
        if (aiMaxTokens < 1) {
            throw new GeneratorException("--ai-max-tokens must be at least 1, was " + aiMaxTokens);
        }
        return AiProviderFactory.create(aiProvider, aiApiKey, aiModel, aiMaxTokens);
    }
}
