package net.unit8.tieto.generator.parser;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Resolves a Repository method parameter type from Java source.
 *
 * <p>Simple/JDK types resolve to {@code null} (they need no description). A
 * domain type resolves to its {@link TypeDef}; a sealed Specification hierarchy
 * resolves recursively to all permitted subtypes so the AI can see every
 * composite and leaf. Permitted subtypes are located either nested in the same
 * compilation unit or as sibling files in the same package.</p>
 */
public final class TypeResolver {

    private static final Set<String> SIMPLE_TYPES = Set.of(
            "boolean", "byte", "short", "int", "long", "float", "double", "char", "void",
            "Boolean", "Byte", "Short", "Integer", "Long", "Float", "Double", "Character",
            "String", "BigDecimal", "BigInteger", "UUID", "Object",
            "LocalDate", "LocalTime", "LocalDateTime", "OffsetDateTime", "ZonedDateTime", "Instant"
    );

    private final Path sourceDir;

    public TypeResolver(Path sourceDir) {
        this.sourceDir = sourceDir;
    }

    /**
     * Resolves a parameter type referenced from the given compilation unit.
     *
     * @param rawType the parameter type as written (e.g. {@code OrderSpec})
     * @param context the compilation unit that references the type (for imports
     *                and package resolution)
     * @return the resolved {@link TypeDef}, or {@code null} for simple/JDK types
     *         or types whose source cannot be located
     */
    public TypeDef resolve(String rawType, CompilationUnit context) {
        // Generic wrappers (List<...>, Optional<...>, etc.) are not described.
        if (rawType.contains("<")) {
            return null;
        }
        String typeName = rawType.trim();
        if (SIMPLE_TYPES.contains(typeName)) {
            return null;
        }

        Located located = locate(typeName, context);
        if (located == null) {
            return null;
        }
        return toTypeDef(located.declaration(), located.unit(), null);
    }

    private TypeDef toTypeDef(TypeDeclaration<?> decl, CompilationUnit unit, String kind) {
        String javadoc = decl.getJavadoc()
                .map(jd -> jd.getDescription().toText())
                .orElse("");

        List<ComponentDef> components = components(decl);

        boolean sealed = decl.getModifiers().stream()
                .anyMatch(m -> m.getKeyword() == Modifier.Keyword.SEALED);

        List<TypeDef> subtypes = new ArrayList<>();
        if (sealed && decl instanceof ClassOrInterfaceDeclaration cid) {
            List<String> subNames = new ArrayList<>();
            for (ClassOrInterfaceType permitted : cid.getPermittedTypes()) {
                subNames.add(permitted.getNameAsString());
            }
            // permits clause may be omitted when all subtypes are nested/in-file;
            // fall back to nested type declarations so they are not silently dropped.
            if (subNames.isEmpty()) {
                cid.getMembers().forEach(m -> {
                    if (m instanceof TypeDeclaration<?> td) {
                        subNames.add(td.getNameAsString());
                    }
                });
            }
            for (String subName : subNames) {
                Located sub = locate(subName, unit);
                if (sub != null) {
                    subtypes.add(toTypeDef(sub.declaration(), sub.unit(), kindOf(subName)));
                }
            }
        }

        String qualifiedName = unit.getPackageDeclaration()
                .map(pd -> pd.getNameAsString() + "." + decl.getNameAsString())
                .orElse(decl.getNameAsString());

        return new TypeDef(
                decl.getNameAsString(),
                qualifiedName,
                kind,
                sealed,
                javadoc,
                components,
                subtypes
        );
    }

    private static List<ComponentDef> components(TypeDeclaration<?> decl) {
        List<ComponentDef> result = new ArrayList<>();
        if (decl instanceof RecordDeclaration rd) {
            rd.getParameters().forEach(p ->
                    result.add(new ComponentDef(p.getNameAsString(), p.getTypeAsString())));
        } else if (decl instanceof ClassOrInterfaceDeclaration cid && !cid.isInterface()) {
            for (FieldDeclaration field : cid.getFields()) {
                field.getVariables().forEach(v ->
                        result.add(new ComponentDef(v.getNameAsString(), v.getTypeAsString())));
            }
        }
        return result;
    }

    /**
     * Locates a type declaration by simple name: first among the types declared
     * in the given unit (including nested), then as a sibling source file.
     */
    private Located locate(String typeName, CompilationUnit unit) {
        TypeDeclaration<?> inUnit = findType(unit, typeName);
        if (inUnit != null) {
            return new Located(inUnit, unit);
        }

        for (String fqcn : candidateFqcns(typeName, unit)) {
            Path file = sourceDir.resolve(fqcn.replace('.', '/') + ".java");
            if (!Files.exists(file)) {
                continue;
            }
            try {
                ParseResult<CompilationUnit> result = RepositoryParser.JAVA_PARSER.parse(file);
                CompilationUnit cu = result.getResult().orElse(null);
                if (cu == null) {
                    continue;
                }
                TypeDeclaration<?> inFile = findType(cu, typeName);
                if (inFile != null) {
                    return new Located(inFile, cu);
                }
            } catch (IOException e) {
                // try the next candidate
            }
        }
        return null;
    }

    private static TypeDeclaration<?> findType(CompilationUnit unit, String typeName) {
        for (TypeDeclaration<?> td : unit.findAll(TypeDeclaration.class)) {
            if (td.getNameAsString().equals(typeName)) {
                return td;
            }
        }
        return null;
    }

    /**
     * Candidate fully-qualified names for a simple type name, in precedence
     * order: explicit imports, the current package, then each wildcard-imported
     * package. The caller tries each until a source file is found, so a type
     * brought in via {@code import foo.bar.*;} is not silently dropped.
     */
    private static List<String> candidateFqcns(String typeName, CompilationUnit unit) {
        List<String> candidates = new ArrayList<>();
        List<String> wildcardPackages = new ArrayList<>();
        for (var imp : unit.getImports()) {
            String name = imp.getNameAsString();
            if (imp.isAsterisk()) {
                wildcardPackages.add(name);
            } else if (name.equals(typeName) || name.endsWith("." + typeName)) {
                candidates.add(name);
            }
        }
        unit.getPackageDeclaration()
                .ifPresent(pd -> candidates.add(pd.getNameAsString() + "." + typeName));
        for (String pkg : wildcardPackages) {
            candidates.add(pkg + "." + typeName);
        }
        return candidates;
    }

    static String kindOf(String simpleName) {
        if (simpleName.isEmpty()) {
            return simpleName;
        }
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    private record Located(TypeDeclaration<?> declaration, CompilationUnit unit) {}
}
