package net.unit8.tieto.generator.output;

import net.unit8.tieto.generator.parser.ComponentDef;
import net.unit8.tieto.generator.parser.MethodSpec;
import net.unit8.tieto.generator.parser.ParameterSpec;
import net.unit8.tieto.generator.parser.TypeDef;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Generates a runnable, domain-level round-trip test for a Repository.
 *
 * <p>The static and deploy-time checks verify the generated functions in
 * isolation; this emits a test that exercises the Repository the way an
 * application does — through the real tieto-core proxy, with domain objects, over
 * a Testcontainers PostgreSQL loaded with the project's own schema and seed.</p>
 *
 * <p>The oracle is scaffold-first: where a round-trip can be inferred mechanically
 * (a finder with simple arguments, or a {@code save} paired with a finder that
 * reads back by one of the saved object's fields) the assertion is written for
 * you; everywhere else a {@code @Disabled} test with a {@code // TODO} marks the
 * spot to fill in. No false assertion is ever emitted. Building a valid aggregate
 * that satisfies foreign keys cannot be done generically, so that step is always
 * left to the developer.</p>
 *
 * <p>The class is pure: {@link #generate} maps the parsed methods to Java source.</p>
 */
public final class RepositoryTestGenerator {

    /**
     * Inputs that are not derivable from the method list.
     *
     * @param packageName        the Repository's package (the test's package)
     * @param repositorySimpleName the Repository interface simple name
     * @param functionsResource  classpath resource holding the generated functions SQL
     * @param schemaResource     classpath resource holding the table DDL
     * @param seedResource       classpath resource holding seed data
     */
    public record Options(
            String packageName,
            String repositorySimpleName,
            String functionsResource,
            String schemaResource,
            String seedResource) {}

    // Java source expressions for the simple parameter types we can synthesize.
    // Kept in step with ReadSmokeVerifier.SYNTHESIZERS so an eligible read always
    // has a literal here.
    private static final Map<String, String> LITERALS = Map.ofEntries(
            Map.entry("long", "0L"), Map.entry("Long", "0L"),
            Map.entry("int", "0"), Map.entry("Integer", "0"),
            Map.entry("short", "(short) 0"), Map.entry("Short", "(short) 0"),
            Map.entry("byte", "(byte) 0"), Map.entry("Byte", "(byte) 0"),
            Map.entry("double", "0.0"), Map.entry("Double", "0.0"),
            Map.entry("float", "0.0f"), Map.entry("Float", "0.0f"),
            Map.entry("boolean", "false"), Map.entry("Boolean", "false"),
            Map.entry("BigDecimal", "java.math.BigDecimal.ZERO"),
            Map.entry("BigInteger", "java.math.BigInteger.ZERO"),
            Map.entry("String", "\"tieto-smoke\""),
            Map.entry("CharSequence", "\"tieto-smoke\""),
            Map.entry("UUID", "java.util.UUID.fromString(\"00000000-0000-0000-0000-000000000000\")"),
            Map.entry("LocalDate", "java.time.LocalDate.of(2000, 1, 1)"),
            Map.entry("LocalTime", "java.time.LocalTime.of(0, 0)"),
            Map.entry("LocalDateTime", "java.time.LocalDateTime.of(2000, 1, 1, 0, 0)"),
            Map.entry("OffsetDateTime",
                    "java.time.OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC)"));

    public String generate(List<MethodSpec> methods, Options options) {
        String className = options.repositorySimpleName() + "RoundTripTest";
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, options, className);
        java.util.Set<String> usedTestNames = new java.util.HashSet<>();
        for (MethodSpec method : methods) {
            appendTest(sb, method, methods, uniqueTestName(method.name(), usedTestNames),
                    options.packageName());
        }
        appendHelpers(sb, options);
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * A test-method base name unique within the class. Overloaded repository methods
     * share a Java name, but two @Test methods cannot, so a collision gets a numeric
     * suffix.
     */
    private static String uniqueTestName(String methodName, java.util.Set<String> used) {
        if (used.add(methodName)) {
            return methodName;
        }
        for (int i = 2; ; i++) {
            String candidate = methodName + "_" + i;
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }

    private void appendHeader(StringBuilder sb, Options options, String className) {
        String repo = options.repositorySimpleName();
        if (!options.packageName().isEmpty()) {
            sb.append("package ").append(options.packageName()).append(";\n\n");
        }
        sb.append("""
                import java.io.IOException;
                import java.io.InputStream;
                import java.nio.charset.StandardCharsets;
                import java.sql.Connection;
                import java.sql.SQLException;
                import java.sql.Statement;
                import javax.sql.DataSource;
                import org.junit.jupiter.api.BeforeAll;
                import org.junit.jupiter.api.Disabled;
                import org.junit.jupiter.api.Test;
                import org.postgresql.ds.PGSimpleDataSource;
                import org.testcontainers.containers.PostgreSQLContainer;
                import org.testcontainers.junit.jupiter.Container;
                import org.testcontainers.junit.jupiter.Testcontainers;
                import net.unit8.tieto.core.TietoClient;

                import static org.assertj.core.api.Assertions.assertThat;

                """);
        sb.append("""
                /**
                 * Generated by tieto-generator. Domain-level round-trip test for %1$s.
                 *
                 * <p>Requires these test dependencies on your module: tieto-core,
                 * org.postgresql:postgresql, org.junit.jupiter:junit-jupiter,
                 * org.assertj:assertj-core, org.testcontainers:postgresql and
                 * org.testcontainers:junit-jupiter.</p>
                 *
                 * <p>Edit the @Disabled tests: build a valid aggregate (foreign-key columns
                 * must reference existing rows) and enable them. Strengthen the smoke tests
                 * to assert against your seed data. This file is a starting point, not a
                 * finished suite, and is not overwritten on regeneration unless --force.</p>
                 */
                """.formatted(repo));
        sb.append("@Testcontainers\n");
        sb.append("class ").append(className).append(" {\n\n");
        sb.append("""
                    @Container
                    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

                """);
        sb.append("    static ").append(repo).append(" repository;\n\n");
        sb.append("""
                    @BeforeAll
                    static void setUp() throws Exception {
                        DataSource dataSource = dataSource();
                        runSql(dataSource, SCHEMA_RESOURCE);
                        runSql(dataSource, SEED_RESOURCE);
                        runSql(dataSource, FUNCTIONS_RESOURCE);
                        repository = TietoClient.builder(dataSource).build()
                                .createRepository(%1$s.class);
                    }

                """.formatted(repo));
        sb.append("    private static final String SCHEMA_RESOURCE = \"")
                .append(options.schemaResource()).append("\";\n");
        sb.append("    private static final String SEED_RESOURCE = \"")
                .append(options.seedResource()).append("\";\n");
        sb.append("    private static final String FUNCTIONS_RESOURCE = \"")
                .append(options.functionsResource()).append("\";\n\n");
    }

    private void appendTest(StringBuilder sb, MethodSpec method, List<MethodSpec> all,
                            String testName, String testPackage) {
        if (isAggregateWrite(method)) {
            appendSaveRoundTrip(sb, method, all, testName, testPackage);
        } else if (isReadShaped(method) && allParamsRenderable(method)) {
            appendReadSmoke(sb, method, testName);
        } else {
            appendDisabledScaffold(sb, method, testName);
        }
    }

    private void appendReadSmoke(StringBuilder sb, MethodSpec method, String testName) {
        sb.append("    @Test\n");
        sb.append("    void ").append(testName).append("_smoke() {\n");
        sb.append("        // TODO: strengthen — assert specific rows/values from your seed data.\n");
        sb.append("        assertThat(repository.").append(method.name())
                .append("(").append(renderSimpleArgs(method)).append(")).isNotNull();\n");
        sb.append("    }\n\n");
    }

    private void appendSaveRoundTrip(StringBuilder sb, MethodSpec method, List<MethodSpec> all,
                                     String testName, String testPackage) {
        TypeDef domain = method.parameters().get(0).typeDef();
        String domainType = typeName(domain, testPackage);
        Optional<Readback> readback = findReadback(domain, all);
        sb.append("    @Test\n");
        sb.append("    @Disabled(\"TODO: build a valid ").append(domain.simpleName())
                .append(" (foreign-key columns must reference existing rows), then enable\")\n");
        sb.append("    void ").append(testName).append("_roundTrip() {\n");
        sb.append("        ").append(domainType).append(" saved = null; // TODO: build a valid ")
                .append(domain.simpleName()).append("\n");
        sb.append("        repository.").append(method.name()).append("(saved);\n");
        if (readback.isPresent()) {
            Readback rb = readback.get();
            String call = "repository." + rb.finder().name() + "(saved." + rb.component() + "())";
            sb.append("        ").append(rb.assertion(call)).append("\n");
        } else {
            sb.append("        // TODO: read it back via a finder and assert it equals 'saved'.\n");
        }
        sb.append("    }\n\n");
    }

    private void appendDisabledScaffold(StringBuilder sb, MethodSpec method, String testName) {
        sb.append("    @Test\n");
        sb.append("    @Disabled(\"TODO: build the argument(s) and assert the expected result, then enable\")\n");
        sb.append("    void ").append(testName).append("_test() {\n");
        sb.append("        // ").append(method.name()).append("(")
                .append(method.parameters().stream().map(ParameterSpec::type)
                        .collect(Collectors.joining(", ")))
                .append(") -> ").append(method.returnType()).append("\n");
        sb.append("        // TODO: construct the argument(s) and call repository.")
                .append(method.name()).append("(...), then assert.\n");
        sb.append("    }\n\n");
    }

    /** A finder that reads back a saved object by one of its components. */
    private record Readback(MethodSpec finder, String component, String returnType) {
        String assertion(String call) {
            String r = returnType.strip();
            if (startsWithCollection(r) || r.startsWith("Optional") || r.startsWith("java.util.Optional")) {
                return "assertThat(" + call + ").contains(saved);";
            }
            return "assertThat(" + call + ").isEqualTo(saved);";
        }
    }

    private Optional<Readback> findReadback(TypeDef domain, List<MethodSpec> all) {
        Readback idMatch = null;
        for (MethodSpec candidate : all) {
            if (!isReadShaped(candidate) || candidate.parameters().size() != 1) {
                continue;
            }
            ParameterSpec param = candidate.parameters().get(0);
            if (param.typeDef() != null) {
                continue;
            }
            for (ComponentDef component : domain.components()) {
                if (component.name().equalsIgnoreCase(param.name())
                        && simpleName(component.type()).equals(simpleName(param.type()))) {
                    Readback match = new Readback(candidate, component.name(), candidate.returnType());
                    // Prefer a non-id readback: a saved aggregate's id is database-generated,
                    // so reading back by a business field is more reliable than by id.
                    if (!component.name().equalsIgnoreCase("id")) {
                        return Optional.of(match);
                    }
                    if (idMatch == null) {
                        idMatch = match;
                    }
                }
            }
        }
        return Optional.ofNullable(idMatch);
    }

    private void appendHelpers(StringBuilder sb, Options options) {
        sb.append("""
                    private static DataSource dataSource() {
                        PGSimpleDataSource ds = new PGSimpleDataSource();
                        ds.setUrl(POSTGRES.getJdbcUrl());
                        ds.setUser(POSTGRES.getUsername());
                        ds.setPassword(POSTGRES.getPassword());
                        return ds;
                    }

                    private static void runSql(DataSource dataSource, String resource)
                            throws SQLException, IOException {
                        String sql;
                        try (InputStream in =
                                %1$sRoundTripTest.class.getClassLoader().getResourceAsStream(resource)) {
                            if (in == null) {
                                throw new IOException("SQL resource not found on the classpath: " + resource);
                            }
                            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        }
                        try (Connection conn = dataSource.getConnection();
                                Statement stmt = conn.createStatement()) {
                            stmt.execute(sql);
                        }
                    }
                """.formatted(options.repositorySimpleName()));
    }

    // --- classification helpers ---
    // Read/write classification is owned by ReadSmokeVerifier so the deploy smoke
    // and the emitted test agree on what counts as a read vs a write.

    private static boolean isReadShaped(MethodSpec method) {
        return ReadSmokeVerifier.isReadShaped(method);
    }

    /**
     * Whether the method is a write whose single argument is a domain aggregate
     * (a record with components) — the only shape the save round-trip template
     * fits. Multi-argument writes (e.g. {@code updateStatus(id, status)}) and
     * writes taking an enum or Specification go to the disabled scaffold instead.
     */
    private static boolean isAggregateWrite(MethodSpec method) {
        if (!ReadSmokeVerifier.isWriteName(method.name()) || method.parameters().size() != 1) {
            return false;
        }
        TypeDef typeDef = method.parameters().get(0).typeDef();
        return typeDef != null && !typeDef.sealed() && !typeDef.components().isEmpty();
    }

    /**
     * The simple type names this generator can render a literal for. Must match
     * {@link ReadSmokeVerifier#synthesizableTypeNames()} so a finder the deploy
     * smoke deems eligible also gets an automatic (not @Disabled) test; a test
     * asserts the two sets are equal.
     */
    static java.util.Set<String> renderableTypeNames() {
        return LITERALS.keySet();
    }

    private static boolean allParamsRenderable(MethodSpec method) {
        return method.parameters().stream()
                .allMatch(p -> p.typeDef() == null && LITERALS.containsKey(simpleName(p.type())));
    }

    private static String renderSimpleArgs(MethodSpec method) {
        return method.parameters().stream()
                .map(p -> LITERALS.get(simpleName(p.type())))
                .collect(Collectors.joining(", "));
    }

    private static boolean startsWithCollection(String type) {
        String t = type.strip();
        return t.startsWith("List") || t.startsWith("Collection") || t.startsWith("Set")
                || t.startsWith("Iterable") || t.startsWith("java.util.List")
                || t.startsWith("java.util.Collection") || t.startsWith("java.util.Set");
    }

    /**
     * The name to reference a domain type by in generated code: the simple name when
     * the type is in the test's own package (no import needed) or its package is
     * unknown, else the fully-qualified name so the test compiles without an import
     * regardless of where the model lives.
     */
    private static String typeName(TypeDef typeDef, String testPackage) {
        String qualified = typeDef.qualifiedName();
        if (qualified == null) {
            return typeDef.simpleName();
        }
        int lastDot = qualified.lastIndexOf('.');
        String typePackage = lastDot < 0 ? "" : qualified.substring(0, lastDot);
        return typePackage.equals(testPackage) ? typeDef.simpleName() : qualified;
    }

    private static String simpleName(String type) {
        String t = type.strip();
        int lt = t.indexOf('<');
        if (lt >= 0) {
            t = t.substring(0, lt);
        }
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }
}
