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
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;

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
            // A default/static/private interface method carries an implementation that runs in
            // Java (the proxy dispatches a default method via InvocationHandler.invokeDefault;
            // static/private helpers are called directly), so it is never backed by a generated
            // PostgreSQL function. Generating one would deploy a dead function nothing invokes —
            // or, if the AI cannot produce coherent SQL for a helper, fail the whole run. Any
            // interface method with a body is such a method, so skip it.
            if (md.getBody().isPresent()) {
                System.err.println("Warning: skipping method "
                        + iface.getNameAsString() + "." + md.getNameAsString()
                        + " because it has a body (default/static/private methods run in Java,"
                        + " not as a generated PostgreSQL function)");
                continue;
            }
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
                .map(RepositoryParser::renderJavadoc)
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

    /**
     * Renders a method's Javadoc as the prompt spec: the free-text description followed by
     * its block tags ({@code @param}, {@code @return}, {@code @throws}). The Javadoc is the
     * specification the AI generates from, so behavioural notes written in the tag sections
     * must reach the prompt, not only the leading description.
     */
    private static String renderJavadoc(Javadoc jd) {
        StringBuilder sb = new StringBuilder(jd.getDescription().toText());
        for (JavadocBlockTag tag : jd.getBlockTags()) {
            String content = tag.getContent().toText().strip();
            if (content.isEmpty() && tag.getName().isEmpty()) {
                continue;
            }
            sb.append('\n').append('@').append(tag.getTagName());
            tag.getName().ifPresent(name -> sb.append(' ').append(name));
            if (!content.isEmpty()) {
                sb.append(' ').append(content);
            }
        }
        return sb.toString().strip();
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
