package net.unit8.tieto.core.proxy;

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

    interface SampleRepository {
        Optional<String> findById(Long id);

        List<String> findAll();

        void deleteById(Long id);
    }

    @Test
    void capturesTheMethodReturnHandlerAndParameters() throws Exception {
        Method method = SampleRepository.class.getMethod("findById", Long.class);

        MethodMetadata metadata = MethodMetadata.analyze(SampleRepository.class, method);

        assertThat(metadata.method()).isEqualTo(method);
        assertThat(metadata.returnTypeHandler())
                .isEqualTo(new ReturnTypeHandler.OptionalHandler(String.class));
        assertThat(metadata.parameters()).isEqualTo(ParameterInfo.from(method));
        assertThat(metadata.parameters()).hasSize(1);
    }

    @Test
    void handlesAListReturnWithNoParameters() throws Exception {
        Method method = SampleRepository.class.getMethod("findAll");

        MethodMetadata metadata = MethodMetadata.analyze(SampleRepository.class, method);

        assertThat(metadata.returnTypeHandler())
                .isEqualTo(new ReturnTypeHandler.ListHandler(String.class));
        assertThat(metadata.parameters()).isEmpty();
    }

    @Test
    void handlesAVoidReturn() throws Exception {
        Method method = SampleRepository.class.getMethod("deleteById", Long.class);

        MethodMetadata metadata = MethodMetadata.analyze(SampleRepository.class, method);

        assertThat(metadata.returnTypeHandler()).isEqualTo(new ReturnTypeHandler.VoidHandler());
    }
}
