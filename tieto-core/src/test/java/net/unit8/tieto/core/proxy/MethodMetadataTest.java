package net.unit8.tieto.core.proxy;

import net.unit8.tieto.core.function.DefaultFunctionNameResolver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link MethodMetadata#analyze} composes the return-type handler and
 * parameter metadata for a method (the per-method analysis the proxy caches).
 */
class MethodMetadataTest {

    record Sample(String name) {
    }

    interface SampleRepository {
        Optional<String> findById(Long id);

        List<String> findAll();

        void deleteById(Long id);

        void save(Sample entity);
    }

    @Test
    void capturesTheReturnHandlerAndSimpleParameterContent() throws Exception {
        Method method = SampleRepository.class.getMethod("findById", Long.class);

        MethodMetadata metadata = MethodMetadata.analyze(
                SampleRepository.class, method, new DefaultFunctionNameResolver());

        assertThat(metadata.functionName()).isEqualTo("sample_repository_find_by_id_v1");
        assertThat(metadata.returnTypeHandler())
                .isEqualTo(new ReturnTypeHandler.OptionalHandler(String.class));
        // Assert the analyzed parameter content, not a second call of the analyzer.
        assertThat(metadata.parameters()).hasSize(1);
        ParameterInfo param = metadata.parameters().get(0);
        assertThat(param.index()).isZero();
        assertThat(param.type()).isEqualTo(Long.class);
        assertThat(param.isOptional()).isFalse();
        assertThat(param.isDomainObject()).isFalse();
    }

    @Test
    void classifiesADomainObjectParameter() throws Exception {
        Method method = SampleRepository.class.getMethod("save", Sample.class);

        MethodMetadata metadata = MethodMetadata.analyze(
                SampleRepository.class, method, new DefaultFunctionNameResolver());

        assertThat(metadata.returnTypeHandler()).isEqualTo(new ReturnTypeHandler.VoidHandler());
        ParameterInfo param = metadata.parameters().get(0);
        assertThat(param.type()).isEqualTo(Sample.class);
        assertThat(param.isDomainObject()).isTrue();
    }

    @Test
    void handlesAListReturnWithNoParameters() throws Exception {
        Method method = SampleRepository.class.getMethod("findAll");

        MethodMetadata metadata = MethodMetadata.analyze(
                SampleRepository.class, method, new DefaultFunctionNameResolver());

        assertThat(metadata.returnTypeHandler())
                .isEqualTo(new ReturnTypeHandler.ListHandler(String.class));
        assertThat(metadata.parameters()).isEmpty();
    }

    @Test
    void handlesAVoidReturn() throws Exception {
        Method method = SampleRepository.class.getMethod("deleteById", Long.class);

        MethodMetadata metadata = MethodMetadata.analyze(
                SampleRepository.class, method, new DefaultFunctionNameResolver());

        assertThat(metadata.returnTypeHandler()).isEqualTo(new ReturnTypeHandler.VoidHandler());
    }
}
