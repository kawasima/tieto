package net.unit8.tieto.generator.parser;

import net.unit8.tieto.core.annotation.FunctionVersion;
import net.unit8.tieto.core.function.DefaultFunctionNameResolver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the contract that the generator and the tieto-core runtime derive the
 * same PostgreSQL function name for a method. The two implementations live in
 * separate modules with no shared code; if either drifts (camelToSnake or the
 * {@code _v{N}} format), the generated/deployed function name and the name the
 * runtime proxy calls diverge, and every call fails with "function does not
 * exist". This test fails loudly the moment that happens.
 */
class FunctionNamingAgreementTest {

    interface SampleRepository {
        void findById();
        void findByHTTPStatus();
        void saveOrder2();
        @FunctionVersion(3)
        void search();
    }

    @Test
    void generatorAndRuntimeAgreeOnFunctionNames() {
        DefaultFunctionNameResolver runtime = new DefaultFunctionNameResolver();

        for (Method method : SampleRepository.class.getDeclaredMethods()) {
            int version = method.isAnnotationPresent(FunctionVersion.class)
                    ? method.getAnnotation(FunctionVersion.class).value()
                    : 1;
            MethodSpec spec = new MethodSpec(method.getName(), "void", List.of(), "", version);
            RepositorySpec repo = new RepositorySpec(
                    SampleRepository.class.getName(),
                    SampleRepository.class.getSimpleName(),
                    List.of(spec));

            String generatorName = FunctionNaming.functionName(repo, spec);
            String runtimeName = runtime.resolve(SampleRepository.class, method);

            assertThat(generatorName)
                    .as("function name for %s", method.getName())
                    .isEqualTo(runtimeName);
        }
    }
}
