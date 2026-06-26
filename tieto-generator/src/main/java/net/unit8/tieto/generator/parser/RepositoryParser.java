package net.unit8.tieto.generator.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
        List<MethodSpec> methods = iface.getMethods().stream()
                .map(md -> toMethodSpec(md, cu, typeResolver))
                .toList();

        return new RepositorySpec(fullyQualifiedName, simpleName, methods);
    }

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
        if (a.isSingleMemberAnnotationExpr()) {
            return Integer.parseInt(
                    a.asSingleMemberAnnotationExpr().getMemberValue().toString());
        }
        return 1;
    }

    private static String extractSimpleName(String fullyQualifiedName) {
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot >= 0
                ? fullyQualifiedName.substring(lastDot + 1)
                : fullyQualifiedName;
    }
}
