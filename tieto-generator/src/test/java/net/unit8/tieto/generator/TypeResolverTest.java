package net.unit8.tieto.generator;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import net.unit8.tieto.generator.ai.PromptBuilder;
import net.unit8.tieto.generator.parser.MethodSpec;
import net.unit8.tieto.generator.parser.ParameterSpec;
import net.unit8.tieto.generator.parser.RepositorySpec;
import net.unit8.tieto.generator.parser.TypeDef;
import net.unit8.tieto.generator.parser.TypeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TypeResolverTest {

    private static final JavaParser PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    @TempDir
    Path sourceDir;

    /** A sealed spec with a nested sealed sub-group, in its own package. */
    private void writeFooSpec() throws IOException {
        Path dir = sourceDir.resolve("com/ex/spec");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("FooSpec.java"), """
                package com.ex.spec;
                import java.util.List;
                public sealed interface FooSpec permits FooSpec.And, FooSpec.ByName, FooSpec.Range {
                    record And(List<FooSpec> specs) implements FooSpec {}
                    record ByName(String name) implements FooSpec {}
                    sealed interface Range extends FooSpec permits Range.After, Range.Before {
                        record After(int v) implements Range {}
                        record Before(int v) implements Range {}
                    }
                }
                """);
    }

    private CompilationUnit context(String source) {
        return PARSER.parse(source).getResult().orElseThrow();
    }

    @Test
    void resolvesTypeBroughtInByWildcardImportFromAnotherPackage() throws IOException {
        writeFooSpec();
        CompilationUnit ctx = context("""
                package com.ex.repo;
                import com.ex.spec.*;
                interface FooRepository { void findBy(FooSpec spec); }
                """);

        TypeDef def = new TypeResolver(sourceDir).resolve("FooSpec", ctx);

        assertThat(def).isNotNull();
        assertThat(def.simpleName()).isEqualTo("FooSpec");
        assertThat(def.sealed()).isTrue();
        assertThat(def.subtypes()).extracting(TypeDef::kind)
                .containsExactlyInAnyOrder("and", "byName", "range");
    }

    @Test
    void resolvesNestedSealedSubGroupRecursively() throws IOException {
        writeFooSpec();
        CompilationUnit ctx = context("""
                package com.ex.spec;
                interface FooRepository { void findBy(FooSpec spec); }
                """);

        TypeDef def = new TypeResolver(sourceDir).resolve("FooSpec", ctx);
        TypeDef range = def.subtypes().stream()
                .filter(s -> s.kind().equals("range")).findFirst().orElseThrow();

        assertThat(range.sealed()).isTrue();
        assertThat(range.subtypes()).extracting(TypeDef::kind)
                .containsExactlyInAnyOrder("after", "before");
    }

    @Test
    void qualifiedNameIncludesPackageAndEnclosingTypesForNestedTypes() throws IOException {
        writeFooSpec();
        CompilationUnit ctx = context("""
                package com.ex.spec;
                interface FooRepository { void findBy(FooSpec spec); }
                """);

        TypeDef def = new TypeResolver(sourceDir).resolve("FooSpec", ctx);
        assertThat(def.qualifiedName()).isEqualTo("com.ex.spec.FooSpec");

        TypeDef byName = def.subtypes().stream()
                .filter(s -> s.kind().equals("byName")).findFirst().orElseThrow();
        assertThat(byName.qualifiedName()).isEqualTo("com.ex.spec.FooSpec.ByName");

        TypeDef range = def.subtypes().stream()
                .filter(s -> s.kind().equals("range")).findFirst().orElseThrow();
        TypeDef after = range.subtypes().stream()
                .filter(s -> s.kind().equals("after")).findFirst().orElseThrow();
        assertThat(after.qualifiedName()).isEqualTo("com.ex.spec.FooSpec.Range.After");
    }

    @Test
    void genericParameterTypeResolvesToNull() throws IOException {
        writeFooSpec();
        CompilationUnit ctx = context("""
                package com.ex.repo;
                import com.ex.spec.*;
                import java.util.List;
                interface FooRepository { void findBy(List<FooSpec> specs); }
                """);

        assertThat(new TypeResolver(sourceDir).resolve("List<FooSpec>", ctx)).isNull();
    }

    @Test
    void promptIncludesDeeplyNestedKinds() throws IOException {
        writeFooSpec();
        CompilationUnit ctx = context("""
                package com.ex.spec;
                interface FooRepository { void findBy(FooSpec spec); }
                """);
        TypeDef def = new TypeResolver(sourceDir).resolve("FooSpec", ctx);

        MethodSpec method = new MethodSpec("findBy", "void",
                List.of(new ParameterSpec("spec", "FooSpec", def)), "", 1);
        RepositorySpec repo = new RepositorySpec("com.ex.spec.FooRepository", "FooRepository",
                List.of(method));

        String prompt = new PromptBuilder().build(repo, method, List.of());

        assertThat(prompt)
                .contains("kind=\"range\"")
                .contains("kind=\"after\"")
                .contains("kind=\"before\"");
    }
}
