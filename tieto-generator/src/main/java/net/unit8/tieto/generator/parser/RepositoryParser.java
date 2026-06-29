package net.unit8.tieto.generator.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parses a Repository interface Java source file using JavaParser,
 * extracting method signatures and Javadoc comments.
 */
public class RepositoryParser {

    /** Configured for Java 21 so records and sealed types parse correctly. */
    static final JavaParser JAVA_PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    /**
     * Parses a Repository interface from its source file.
     *
     * @param sourceDir the root Java source directory
     * @param fullyQualifiedName the fully qualified interface name (e.g., com.example.OrderRepository)
     * @return the parsed repository specification
     */
    public RepositorySpec parse(Path sourceDir, String fullyQualifiedName) {
        Path sourceFile = sourceDir.resolve(
                fullyQualifiedName.replace('.', '/') + ".java");

        CompilationUnit cu;
        try {
            ParseResult<CompilationUnit> result = JAVA_PARSER.parse(sourceFile);
            cu = result.getResult().orElseThrow(() -> new GeneratorException(
                    "Failed to parse source file: " + sourceFile + " " + result.getProblems()));
        } catch (IOException e) {
            throw new GeneratorException(
                    "Failed to parse source file: " + sourceFile, e);
        }

        String simpleName = extractSimpleName(fullyQualifiedName);

        ClassOrInterfaceDeclaration iface = cu.getInterfaceByName(simpleName)
                .orElseThrow(() -> new GeneratorException(
                        "Interface not found: " + fullyQualifiedName));

        TypeResolver typeResolver = new TypeResolver(sourceDir);
        List<MethodSpec> methods = collectMethods(iface, cu, typeResolver).stream()
                .map(owned -> toMethodSpec(owned.method(), owned.unit(), typeResolver))
                .toList();

        return new RepositorySpec(fullyQualifiedName, simpleName, methods);
    }

    /**
     * Collects the methods of the repository interface and every super-interface
     * reachable through its {@code extends} clause. A method declared in a
     * sub-interface shadows the same-signature method from a super-interface, so
     * an override is emitted once. A super-interface whose source cannot be
     * located (e.g. an external library interface like Spring Data's
     * {@code CrudRepository}) is skipped with a warning rather than failing.
     */
    private List<OwnedMethod> collectMethods(ClassOrInterfaceDeclaration iface,
                                             CompilationUnit unit, TypeResolver typeResolver) {
        LinkedHashMap<String, OwnedMethod> bySignature = new LinkedHashMap<>();
        collectInto(iface, unit, typeResolver, bySignature, new HashSet<>());
        return new ArrayList<>(bySignature.values());
    }

    private void collectInto(ClassOrInterfaceDeclaration iface, CompilationUnit unit,
                             TypeResolver typeResolver, LinkedHashMap<String, OwnedMethod> acc,
                             Set<String> visited) {
        String identity = iface.getFullyQualifiedName().orElse(iface.getNameAsString());
        if (!visited.add(identity)) {
            return;
        }
        // Sub-interface methods are recorded before recursing into supers, so an
        // overriding declaration (same signature) wins over the inherited one.
        Set<String> typeVariables = iface.getTypeParameters().stream()
                .map(tp -> tp.getNameAsString())
                .collect(Collectors.toSet());
        for (MethodDeclaration md : iface.getMethods()) {
            // A method whose return or parameter type is one of the declaring
            // interface's type variables (E, ID, ...) cannot be resolved without
            // type-argument substitution, which we do not perform. Emitting it
            // verbatim would feed meaningless type names to SQL generation, so
            // skip it loudly rather than generate a wrong function.
            if (usesTypeVariable(md, typeVariables)) {
                System.err.println("Warning: skipping inherited method "
                        + iface.getNameAsString() + "." + md.getNameAsString()
                        + " because it uses unresolved type variable(s) " + typeVariables
                        + "; generic super-interface type arguments are not substituted");
                continue;
            }
            acc.putIfAbsent(signature(md), new OwnedMethod(md, unit));
        }
        for (ClassOrInterfaceType extended : iface.getExtendedTypes()) {
            TypeResolver.SourceInterface superInterface =
                    typeResolver.locateInterface(extended.getNameAsString(), unit);
            if (superInterface != null) {
                collectInto(superInterface.declaration(), superInterface.unit(),
                        typeResolver, acc, visited);
            } else {
                System.err.println("Warning: cannot locate source for super-interface "
                        + extended.getNameAsString() + " extended by " + iface.getNameAsString()
                        + "; its inherited methods will not be generated");
            }
        }
    }

    /** True if the method's return or any parameter type references one of the given type variables. */
    private static boolean usesTypeVariable(MethodDeclaration md, Set<String> typeVariables) {
        if (typeVariables.isEmpty()) {
            return false;
        }
        return Stream.concat(Stream.of(md.getType()), md.getParameters().stream().map(p -> p.getType()))
                .flatMap(type -> type.findAll(ClassOrInterfaceType.class).stream())
                .anyMatch(t -> typeVariables.contains(t.getNameAsString()));
    }

    /**
     * Name plus parameter types, used to detect an override. Parameter types are
     * normalized to simple names (e.g. {@code java.util.List<com.example.Order>}
     * -> {@code List<Order>}) so a genuine override spelled with a different but
     * identical type is not mistaken for a separate overload. Without a symbol
     * solver this is best-effort, not a full erasure.
     */
    private static String signature(MethodDeclaration md) {
        return md.getNameAsString() + "(" + md.getParameters().stream()
                .map(p -> normalizeType(p.getType().asString()))
                .reduce((a, b) -> a + "," + b)
                .orElse("") + ")";
    }

    private static String normalizeType(String type) {
        return type.replaceAll("\\s+", "")
                .replaceAll("(?:[A-Za-z_$][\\w$]*\\.)+([A-Za-z_$][\\w$]*)", "$1");
    }

    /** A method declaration paired with the compilation unit that declares it. */
    private record OwnedMethod(MethodDeclaration method, CompilationUnit unit) {}

    private MethodSpec toMethodSpec(MethodDeclaration md, CompilationUnit cu, TypeResolver typeResolver) {
        String javadoc = md.getJavadoc()
                .map(jd -> jd.getDescription().toText())
                .orElse("");

        List<ParameterSpec> params = md.getParameters().stream()
                .map(p -> new ParameterSpec(
                        p.getNameAsString(),
                        p.getTypeAsString(),
                        typeResolver.resolve(p.getTypeAsString(), cu)))
                .toList();

        int version = extractVersion(md);

        return new MethodSpec(
                md.getNameAsString(),
                md.getTypeAsString(),
                params,
                javadoc,
                version
        );
    }

    private static int extractVersion(MethodDeclaration md) {
        Optional<AnnotationExpr> annotation = md.getAnnotationByName("FunctionVersion");
        if (annotation.isEmpty()) {
            return 1;
        }
        AnnotationExpr a = annotation.get();
        // @FunctionVersion(2)
        if (a.isSingleMemberAnnotationExpr()) {
            return parseVersion(md, a.asSingleMemberAnnotationExpr().getMemberValue());
        }
        // @FunctionVersion(value = 2) — the normal member-value form, equivalent to the above
        if (a.isNormalAnnotationExpr()) {
            return a.asNormalAnnotationExpr().getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals("value"))
                    .findFirst()
                    .map(pair -> parseVersion(md, pair.getValue()))
                    .orElse(1);
        }
        // @FunctionVersion (marker) — falls back to the annotation default
        return 1;
    }

    private static int parseVersion(MethodDeclaration md, Expression value) {
        if (!value.isIntegerLiteralExpr()) {
            throw new GeneratorException(
                    "@FunctionVersion on " + md.getNameAsString()
                            + " must be an integer literal, but was: " + value);
        }
        return value.asIntegerLiteralExpr().asNumber().intValue();
    }

    private static String extractSimpleName(String fullyQualifiedName) {
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot >= 0
                ? fullyQualifiedName.substring(lastDot + 1)
                : fullyQualifiedName;
    }
}
