package net.unit8.tieto.generator.command;

import net.unit8.tieto.generator.ai.AiProvider;
import net.unit8.tieto.generator.ai.AiProviderFactory;
import net.unit8.tieto.generator.ai.GeneratedFunction;
import net.unit8.tieto.generator.ai.GeneratedSqlValidator;
import net.unit8.tieto.generator.ai.PromptBuilder;
import net.unit8.tieto.generator.parser.FunctionNaming;
import net.unit8.tieto.generator.parser.GeneratorException;
import net.unit8.tieto.generator.output.DirectDeployer;
import net.unit8.tieto.generator.output.ReadSmokeVerifier;
import net.unit8.tieto.generator.output.RepositoryTestGenerator;
import net.unit8.tieto.generator.output.SignatureVerifier;
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

    @Option(names = "--emit-test",
            description = "Also emit a domain-level round-trip JUnit test for the repository"
                    + " (runs it through the tieto-core proxy against Testcontainers).")
    private boolean emitTest;

    @Option(names = "--test-output-dir",
            description = "Directory for the generated test source. Default: the --source-dir with"
                    + " src/main/java replaced by src/test/java.")
    private Path testOutputDir;

    @Option(names = "--test-resources-dir",
            description = "Directory for the generated test resources (the functions SQL the test"
                    + " loads). Default: the --source-dir with src/main/java replaced by"
                    + " src/test/resources.")
    private Path testResourcesDir;

    @Option(names = "--schema-sql", defaultValue = "db/01_schema.sql",
            description = "Classpath resource holding the table DDL, loaded by the generated test."
                    + " Default: ${DEFAULT-VALUE}")
    private String schemaSql;

    @Option(names = "--seed-sql", defaultValue = "db/02_testdata.sql",
            description = "Classpath resource holding seed data, loaded by the generated test."
                    + " Default: ${DEFAULT-VALUE}")
    private String seedSql;

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

        // Fail before generating/deploying anything if two methods would map to
        // the same PostgreSQL function name (overloads at the same version).
        FunctionNaming.checkNoCollisions(repoSpec);

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
        List<MethodSpec> generatedMethods = new ArrayList<>();
        List<MethodSpec> deployedSpecMethods = new ArrayList<>();
        for (MethodSpec method : repoSpec.methods()) {
            String versionedName = FunctionNaming.functionName(repoSpec, method);

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
            generatedMethods.add(method);
            if (hasSpecParameter(method)) {
                deployedSpecMethods.add(method);
            }
            System.out.println("  -> " + generated.functionName());
        }

        if (functions.isEmpty() && !emitTest) {
            System.out.println("No functions to generate (all versions up to date)");
            return 0;
        }

        // 4. Output
        if (functions.isEmpty()) {
            System.out.println("No new functions to generate; emitting the test only"
                    + " (existing functions are read from the database).");
        } else if ("deploy".equals(outputMode)) {
            // Deploy and verify every function in one transaction: commit only if each
            // function's signature matches its method, each read smoke-runs without a SQL
            // error, and each spec probe proves leaf values are bound, not concatenated.
            List<DirectDeployer.DeployVerification> verifications =
                    buildVerifications(repoSpec, generatedMethods, deployedSpecMethods);
            try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
                new DirectDeployer().deploy(conn, functions, verifications);
            }
            System.out.println("Deployed " + functions.size() + " functions to database");
        } else {
            // Non-force runs skip functions already in the file, so append the new ones
            // to preserve them; --force regenerates everything, so rewrite the whole file.
            new SqlFileWriter().write(outputDir, repoSpec.simpleName(), functions, force);
            System.out.println("Wrote SQL files to " + outputDir);
        }

        if (emitTest) {
            emitRoundTripTest(repoSpec, functions);
        }

        return 0;
    }

    /**
     * Writes the generated functions to a test resource and a domain-level round-trip
     * test source. The functions resource is always (re)written; the test source is left
     * alone if it already exists (so hand edits survive) unless {@code --force}.
     */
    private void emitRoundTripTest(RepositorySpec repoSpec, List<GeneratedFunction> functions)
            throws IOException {
        String functionsResource = "tieto/" + FunctionNaming.camelToSnake(repoSpec.simpleName()) + "_functions.sql";
        Path resourcesDir = testResourcesDir != null ? testResourcesDir
                : deriveTestDir("resources");
        Path resourceFile = resourcesDir.resolve(functionsResource);
        Files.createDirectories(resourceFile.getParent());
        Files.writeString(resourceFile, functionsResourceSql(repoSpec, functions));
        System.out.println("Wrote test resource " + resourceFile);

        String packageName = packageOf(repoSpec.fullyQualifiedName());
        RepositoryTestGenerator.Options options = new RepositoryTestGenerator.Options(
                packageName, repoSpec.simpleName(), functionsResource, schemaSql, seedSql);
        String source = new RepositoryTestGenerator().generate(repoSpec.methods(), options);

        Path sourceDirForTest = testOutputDir != null ? testOutputDir : deriveTestDir("java");
        Path testFile = sourceDirForTest
                .resolve(packageName.replace('.', '/'))
                .resolve(repoSpec.simpleName() + "RoundTripTest.java");
        if (Files.exists(testFile) && !force) {
            System.out.println("Skipping test " + testFile + " (already exists; use --force to overwrite)");
            return;
        }
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, source);
        System.out.println("Wrote round-trip test " + testFile);
    }

    /**
     * Derives a test directory from {@code --source-dir} by replacing the
     * {@code src/main/java} path segments with {@code src/test/<leaf>}. Matching is
     * on whole path segments (so a directory like {@code javadoc} is not mistaken
     * for {@code java}), and only the first occurrence is rewritten. Falls back to
     * the source dir itself (with a warning) when that segment sequence is absent.
     */
    private Path deriveTestDir(String leaf) {
        Path src = sourceDir.normalize();
        int n = src.getNameCount();
        for (int i = 0; i + 3 <= n; i++) {
            if (src.getName(i).toString().equals("src")
                    && src.getName(i + 1).toString().equals("main")
                    && src.getName(i + 2).toString().equals("java")) {
                Path rebuilt = Path.of("src", "test", leaf);
                if (i > 0) {
                    rebuilt = src.subpath(0, i).resolve(rebuilt);
                }
                if (i + 3 < n) {
                    rebuilt = rebuilt.resolve(src.subpath(i + 3, n));
                }
                Path root = src.getRoot();
                return root != null ? root.resolve(rebuilt) : rebuilt;
            }
        }
        System.err.println("Could not derive a test directory from --source-dir " + sourceDir
                + " (no src/main/java segment); using it as-is. Pass --test-output-dir"
                + " / --test-resources-dir to control the location.");
        return sourceDir;
    }

    /**
     * The SQL the generated test deploys. In deploy mode every repository function
     * (main and any {@code _spec_to_sql} helper) is already in the database, so the
     * resource is read back complete via {@code pg_get_functiondef} regardless of
     * how many were (re)generated this run. In file mode the database may not hold
     * them, so this run's generated functions are used and an incomplete set (some
     * versions skipped without {@code --force}) is warned about.
     */
    private String functionsResourceSql(RepositorySpec repoSpec, List<GeneratedFunction> functions) {
        if ("deploy".equals(outputMode)) {
            return fetchDeployedFunctionsSql(repoSpec);
        }
        if (functions.size() < repoSpec.methods().size()) {
            System.err.println("Warning: the generated test fixture only contains the "
                    + functions.size() + " function(s) generated this run; "
                    + (repoSpec.methods().size() - functions.size()) + " already-present function(s)"
                    + " are not in it. Re-run with --force for a complete, runnable test.");
        }
        return renderFunctionsSql(functions);
    }

    /**
     * Reads the {@code CREATE OR REPLACE FUNCTION} text of every repository function
     * (and {@code _spec_to_sql} helper) from the database via {@code pg_get_functiondef},
     * so the emitted test deploys exactly what is deployed in production.
     */
    private String fetchDeployedFunctionsSql(RepositorySpec repoSpec) {
        List<String> expected = new ArrayList<>();
        for (MethodSpec method : repoSpec.methods()) {
            String name = FunctionNaming.functionName(repoSpec, method);
            expected.add(name);
            if (hasSpecParameter(method)) {
                expected.add(name + "_spec_to_sql");
            }
        }
        java.util.Map<String, String> defs = new java.util.HashMap<>();
        String sql = "SELECT proname, pg_get_functiondef(oid) AS def FROM pg_proc"
                + " WHERE proname = ANY(?) AND pg_function_is_visible(oid)";
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
                java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("text", expected.toArray()));
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    defs.put(rs.getString("proname"), rs.getString("def"));
                }
            }
        } catch (SQLException e) {
            throw new GeneratorException(
                    "Failed to read deployed function definitions for the test fixture: "
                            + e.getMessage(), e);
        }
        StringBuilder out = new StringBuilder("-- Generated by tieto-generator (test fixture)\n\n");
        for (String name : expected) {
            String def = defs.get(name);
            if (def == null) {
                System.err.println("Warning: function " + name + " is not deployed; the generated"
                        + " test will not include it. Deploy it (without --output-mode file) first.");
                continue;
            }
            out.append(def);
            if (!def.endsWith(";")) {
                out.append(';');
            }
            out.append("\n\n");
        }
        return out.toString();
    }

    private static String packageOf(String fullyQualifiedName) {
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot < 0 ? "" : fullyQualifiedName.substring(0, lastDot);
    }

    /** Renders the functions as one SQL script, mirroring {@link SqlFileWriter}. */
    private static String renderFunctionsSql(List<GeneratedFunction> functions) {
        StringBuilder sb = new StringBuilder("-- Generated by tieto-generator (test fixture)\n\n");
        for (GeneratedFunction func : functions) {
            sb.append(func.sqlBody());
            if (!func.sqlBody().endsWith(";")) {
                sb.append(';');
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Builds every deploy-time verification: a signature check for each generated
     * function, a read smoke for each eligible read method, and an injection probe
     * for each spec function. All run inside the deploy transaction, so any failure
     * rolls the whole deploy back.
     */
    private List<DirectDeployer.DeployVerification> buildVerifications(
            RepositorySpec repoSpec, List<MethodSpec> generatedMethods, List<MethodSpec> specMethods) {
        SignatureVerifier signatureVerifier = new SignatureVerifier();
        ReadSmokeVerifier smokeVerifier = new ReadSmokeVerifier();
        List<DirectDeployer.DeployVerification> verifications = new ArrayList<>();
        for (MethodSpec method : generatedMethods) {
            String functionName = FunctionNaming.functionName(repoSpec, method);
            SignatureVerifier.ExpectedSignature expected = SignatureVerifier.expectedFor(method);
            verifications.add(conn -> signatureVerifier.verify(conn, functionName, expected));
            if (ReadSmokeVerifier.isEligible(method)) {
                verifications.add(conn -> smokeVerifier.verify(conn, functionName, method));
            } else if (ReadSmokeVerifier.isReadShaped(method) && !hasSpecParameter(method)) {
                System.out.println("  (not read-smoking " + functionName
                        + "; a parameter is not a synthesizable simple type)");
            }
        }
        // Spec functions are verified behaviorally by the injection probe instead.
        verifications.addAll(injectionProbes(repoSpec, specMethods));
        return verifications;
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
            String functionName = FunctionNaming.functionName(repoSpec, method);
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
        Path outputFile = outputDir.resolve(FunctionNaming.camelToSnake(repositoryName) + ".sql");
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
