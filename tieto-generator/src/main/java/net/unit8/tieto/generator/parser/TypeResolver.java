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
        String typeName = stripGenerics(rawType);
        if (typeName.contains("<") || SIMPLE_TYPES.contains(typeName)) {
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

        return new TypeDef(
                decl.getNameAsString(),
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

        String fqcn = resolveFqcn(typeName, unit);
        if (fqcn == null) {
            return null;
        }
        Path file = sourceDir.resolve(fqcn.replace('.', '/') + ".java");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            ParseResult<CompilationUnit> result = RepositoryParser.JAVA_PARSER.parse(file);
            CompilationUnit cu = result.getResult().orElse(null);
            if (cu == null) {
                return null;
            }
            TypeDeclaration<?> inFile = findType(cu, typeName);
            return inFile != null ? new Located(inFile, cu) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static TypeDeclaration<?> findType(CompilationUnit unit, String typeName) {
        for (TypeDeclaration<?> td : unit.findAll(TypeDeclaration.class)) {
            if (td.getNameAsString().equals(typeName)) {
                return td;
            }
        }
        return null;
    }

    private static String resolveFqcn(String typeName, CompilationUnit unit) {
        for (var imp : unit.getImports()) {
            if (imp.isAsterisk()) continue;
            String name = imp.getNameAsString();
            if (name.equals(typeName) || name.endsWith("." + typeName)) {
                return name;
            }
        }
        return unit.getPackageDeclaration()
                .map(pd -> pd.getNameAsString() + "." + typeName)
                .orElse(typeName);
    }

    private static String stripGenerics(String type) {
        int lt = type.indexOf('<');
        return lt >= 0 ? type.substring(0, lt) : type;
    }

    static String kindOf(String simpleName) {
        if (simpleName.isEmpty()) {
            return simpleName;
        }
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    private record Located(TypeDeclaration<?> declaration, CompilationUnit unit) {}
}
