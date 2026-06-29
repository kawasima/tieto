package net.unit8.tieto.generator.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryParserTest {

    @TempDir
    Path sourceDir;

    private RepositorySpec parseRepo(String methodBody) throws IOException {
        Path pkg = sourceDir.resolve("com/example");
        Files.createDirectories(pkg);
        String src = """
                package com.example;
                import net.unit8.tieto.core.annotation.FunctionVersion;
                public interface OrderRepository {
                %s
                }
                """.formatted(methodBody);
        Files.writeString(pkg.resolve("OrderRepository.java"), src);
        return new RepositoryParser().parse(sourceDir, "com.example.OrderRepository");
    }

    private int versionOf(RepositorySpec spec, String method) {
        return spec.methods().stream()
                .filter(m -> m.name().equals(method))
                .findFirst().orElseThrow()
                .version();
    }

    @Test
    void noAnnotationDefaultsToVersion1() throws IOException {
        RepositorySpec spec = parseRepo("    String findById();");
        assertThat(versionOf(spec, "findById")).isEqualTo(1);
    }

    @Test
    void singleMemberFormIsHonoured() throws IOException {
        RepositorySpec spec = parseRepo("    @FunctionVersion(2) String findById();");
        assertThat(versionOf(spec, "findById")).isEqualTo(2);
    }

    @Test
    void normalMemberFormIsHonoured() throws IOException {
        RepositorySpec spec = parseRepo("    @FunctionVersion(value = 2) String findById();");
        assertThat(versionOf(spec, "findById")).isEqualTo(2);
    }

    @Test
    void nonIntegerLiteralValueFailsLoudly() {
        assertThatThrownBy(() -> parseRepo("    @FunctionVersion(value = SOME_CONSTANT) String findById();"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("FunctionVersion");
    }
}
