package net.unit8.tieto.generator.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FunctionNamingTest {

    private static MethodSpec method(String name, int version, String... paramTypes) {
        List<ParameterSpec> params = java.util.Arrays.stream(paramTypes)
                .map(t -> new ParameterSpec("p", t, null))
                .toList();
        return new MethodSpec(name, "String", params, "", version);
    }

    private static RepositorySpec repo(MethodSpec... methods) {
        return new RepositorySpec("com.example.OrderRepository", "OrderRepository", List.of(methods));
    }

    @Test
    void functionNameIsSnakeCaseWithVersion() {
        assertThat(FunctionNaming.functionName(repo(), method("findById", 2, "Long")))
                .isEqualTo("order_repository_find_by_id_v2");
    }

    @Test
    void distinctNamesDoNotCollide() {
        assertThatCode(() -> FunctionNaming.checkNoCollisions(
                repo(method("findById", 1, "Long"), method("findAll", 1))))
                .doesNotThrowAnyException();
    }

    @Test
    void sameNameDifferentVersionDoesNotCollide() {
        assertThatCode(() -> FunctionNaming.checkNoCollisions(
                repo(method("findById", 1, "Long"), method("findById", 2, "Long"))))
                .doesNotThrowAnyException();
    }

    @Test
    void overloadsAtSameVersionCollideLoudly() {
        assertThatThrownBy(() -> FunctionNaming.checkNoCollisions(
                repo(method("findBy", 1, "Long"), method("findBy", 1, "String"))))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("order_repository_find_by_v1")
                .hasMessageContaining("findBy(Long)")
                .hasMessageContaining("findBy(String)");
    }
}
