package net.unit8.tieto.generator.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryParserTest {

    @TempDir
    Path sourceDir;

    private RepositorySpec parseRepo(String methodBody) throws IOException {
        writeSource("com.example.OrderRepository", """
                package com.example;
                import net.unit8.tieto.core.annotation.FunctionVersion;
                public interface OrderRepository {
                %s
                }
                """.formatted(methodBody));
        return new RepositoryParser().parse(sourceDir, "com.example.OrderRepository");
    }

    private void writeSource(String fullyQualifiedName, String source) throws IOException {
        Path file = sourceDir.resolve(fullyQualifiedName.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private List<String> methodNames(RepositorySpec spec) {
        return spec.methods().stream().map(MethodSpec::name).toList();
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

    @Test
    void defaultStaticAndPrivateMethodsAreExcluded() throws IOException {
        RepositorySpec spec = parseRepo("""
                    java.util.Optional<String> findById(Long id);

                    default String findByIdOrThrow(Long id) {
                        return findById(id).orElseThrow();
                    }

                    static String tableName() {
                        return "orders";
                    }

                    private String helper() {
                        return "x";
                    }
                """);
        assertThat(methodNames(spec))
                .as("only the abstract method is backed by a generated function")
                .containsExactly("findById");
    }

    @Test
    void aDefaultMethodOnAnInheritedInterfaceIsAlsoExcluded() throws IOException {
        writeSource("com.example.BaseRepository", """
                package com.example;
                public interface BaseRepository {
                    String findByCode(String code);
                    default String findByCodeOrThrow(String code) {
                        String r = findByCode(code);
                        if (r == null) throw new IllegalStateException();
                        return r;
                    }
                }
                """);
        writeSource("com.example.OrderRepository", """
                package com.example;
                public interface OrderRepository extends BaseRepository {
                    String findAll();
                }
                """);
        RepositorySpec spec = new RepositoryParser().parse(sourceDir, "com.example.OrderRepository");
        assertThat(methodNames(spec)).containsExactlyInAnyOrder("findAll", "findByCode");
    }

    @Test
    void javadocBlockTagsReachTheSpec() throws IOException {
        RepositorySpec spec = parseRepo("""
                    /**
                     * Find an order by id.
                     * @param id the order id, must be positive
                     * @return the order, or empty when none
                     */
                    java.util.Optional<String> findById(Long id);
                """);
        String javadoc = spec.methods().getFirst().javadoc();
        assertThat(javadoc)
                .contains("Find an order by id.")
                .contains("@param id the order id, must be positive")
                .contains("@return the order, or empty when none");
    }

    @Test
    void inheritedMethodsFromBaseInterfaceAreIncluded() throws IOException {
        writeSource("com.example.BaseRepository", """
                package com.example;
                public interface BaseRepository {
                    String findByCode(String code);
                }
                """);
        writeSource("com.example.OrderRepository", """
                package com.example;
                public interface OrderRepository extends BaseRepository {
                    String findAll();
                }
                """);
        RepositorySpec spec = new RepositoryParser().parse(sourceDir, "com.example.OrderRepository");
        assertThat(methodNames(spec)).containsExactlyInAnyOrder("findAll", "findByCode");
    }

    @Test
    void overriddenMethodAppearsOnce() throws IOException {
        writeSource("com.example.BaseRepository", """
                package com.example;
                public interface BaseRepository {
                    String findById(String id);
                }
                """);
        writeSource("com.example.OrderRepository", """
                package com.example;
                public interface OrderRepository extends BaseRepository {
                    String findById(String id);
                }
                """);
        RepositorySpec spec = new RepositoryParser().parse(sourceDir, "com.example.OrderRepository");
        assertThat(methodNames(spec)).containsExactly("findById");
    }

    @Test
    void transitivelyInheritedMethodsAreIncluded() throws IOException {
        writeSource("com.example.GrandBase", """
                package com.example;
                public interface GrandBase {
                    String fromGrandBase();
                }
                """);
        writeSource("com.example.BaseRepository", """
                package com.example;
                public interface BaseRepository extends GrandBase {
                    String fromBase();
                }
                """);
        writeSource("com.example.OrderRepository", """
                package com.example;
                public interface OrderRepository extends BaseRepository {
                    String fromOrder();
                }
                """);
        RepositorySpec spec = new RepositoryParser().parse(sourceDir, "com.example.OrderRepository");
        assertThat(methodNames(spec))
                .containsExactlyInAnyOrder("fromOrder", "fromBase", "fromGrandBase");
    }

    @Test
    void methodsUsingUnresolvedSuperInterfaceTypeVariablesAreSkipped() throws IOException {
        writeSource("com.example.BaseRepository", """
                package com.example;
                public interface BaseRepository<E, ID> {
                    E findById(ID id);
                    long count();
                }
                """);
        writeSource("com.example.OrderRepository", """
                package com.example;
                public interface OrderRepository extends BaseRepository<String, Long> {
                    String findAll();
                }
                """);
        RepositorySpec spec = new RepositoryParser().parse(sourceDir, "com.example.OrderRepository");
        // findById(ID) -> E references the base's type variables and is skipped;
        // count() and findAll() carry no type variables and are kept.
        assertThat(methodNames(spec)).containsExactlyInAnyOrder("findAll", "count");
    }

    @Test
    void overrideWithDifferentlySpelledTypeIsDedupedOnce() throws IOException {
        writeSource("com.example.BaseRepository", """
                package com.example;
                public interface BaseRepository {
                    String findById(java.lang.String id);
                }
                """);
        writeSource("com.example.OrderRepository", """
                package com.example;
                public interface OrderRepository extends BaseRepository {
                    String findById(String id);
                }
                """);
        RepositorySpec spec = new RepositoryParser().parse(sourceDir, "com.example.OrderRepository");
        assertThat(methodNames(spec)).containsExactly("findById");
    }

    @Test
    void unresolvableSuperInterfaceDoesNotDropOwnMethods() throws IOException {
        writeSource("com.example.OrderRepository", """
                package com.example;
                import org.springframework.data.repository.CrudRepository;
                public interface OrderRepository extends CrudRepository<String, Long> {
                    String findAll();
                }
                """);
        RepositorySpec spec = new RepositoryParser().parse(sourceDir, "com.example.OrderRepository");
        assertThat(methodNames(spec)).containsExactly("findAll");
    }
}
