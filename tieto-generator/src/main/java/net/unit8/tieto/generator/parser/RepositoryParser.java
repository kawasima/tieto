package net.unit8.tieto.generator.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Parses a Repository interface Java source file using JavaParser,
 * extracting method signatures and Javadoc comments.
 */
public class RepositoryParser {

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
            cu = StaticJavaParser.parse(sourceFile);
        } catch (IOException e) {
            throw new GeneratorException(
                    "Failed to parse source file: " + sourceFile, e);
        }

        String simpleName = extractSimpleName(fullyQualifiedName);

        ClassOrInterfaceDeclaration iface = cu.getInterfaceByName(simpleName)
                .orElseThrow(() -> new GeneratorException(
                        "Interface not found: " + fullyQualifiedName));

        List<MethodSpec> methods = iface.getMethods().stream()
                .map(this::toMethodSpec)
                .toList();

        return new RepositorySpec(fullyQualifiedName, simpleName, methods);
    }

    private MethodSpec toMethodSpec(MethodDeclaration md) {
        String javadoc = md.getJavadoc()
                .map(jd -> jd.getDescription().toText())
                .orElse("");

        List<ParameterSpec> params = md.getParameters().stream()
                .map(p -> new ParameterSpec(
                        p.getNameAsString(),
                        p.getTypeAsString()))
                .toList();

        return new MethodSpec(
                md.getNameAsString(),
                md.getTypeAsString(),
                params,
                javadoc
        );
    }

    private static String extractSimpleName(String fullyQualifiedName) {
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot >= 0
                ? fullyQualifiedName.substring(lastDot + 1)
                : fullyQualifiedName;
    }
}
