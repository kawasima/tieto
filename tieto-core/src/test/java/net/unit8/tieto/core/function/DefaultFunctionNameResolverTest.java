package net.unit8.tieto.core.function;

import net.unit8.tieto.core.annotation.FunctionVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultFunctionNameResolverTest {

    private final DefaultFunctionNameResolver resolver = new DefaultFunctionNameResolver();

    @ParameterizedTest
    @CsvSource({
            "OrderRepository, order_repository",
            "UserAccountRepository, user_account_repository",
            "ABCService, abc_service"
    })
    void camelToSnake_convertsCorrectly(String camel, String expected) {
        assertThat(DefaultFunctionNameResolver.camelToSnake(camel))
                .isEqualTo(expected);
    }

    @Test
    void resolve_defaultsToV1() throws NoSuchMethodException {
        var method = SampleRepository.class.getMethod("findById", Long.class);
        String result = resolver.resolve(SampleRepository.class, method);
        assertThat(result).isEqualTo("sample_repository_find_by_id_v1");
    }

    @Test
    void resolve_handlesComplexMethodName() throws NoSuchMethodException {
        var method = SampleRepository.class.getMethod("findByCustomerIdAndStatus",
                String.class, String.class);
        String result = resolver.resolve(SampleRepository.class, method);
        assertThat(result).isEqualTo("sample_repository_find_by_customer_id_and_status_v1");
    }

    @Test
    void resolve_handlesVoidMethod() throws NoSuchMethodException {
        var method = SampleRepository.class.getMethod("save", Object.class);
        String result = resolver.resolve(SampleRepository.class, method);
        assertThat(result).isEqualTo("sample_repository_save_v1");
    }

    @Test
    void resolve_readsVersionAnnotation() throws NoSuchMethodException {
        var method = VersionedRepository.class.getMethod("findById", Long.class);
        String result = resolver.resolve(VersionedRepository.class, method);
        assertThat(result).isEqualTo("versioned_repository_find_by_id_v3");
    }

    @Test
    void resolve_annotationDefaultIsV1() throws NoSuchMethodException {
        var method = VersionedRepository.class.getMethod("save", Object.class);
        String result = resolver.resolve(VersionedRepository.class, method);
        assertThat(result).isEqualTo("versioned_repository_save_v1");
    }

    interface SampleRepository {
        Optional<Object> findById(Long id);
        List<Object> findByCustomerIdAndStatus(String customerId, String status);
        void save(Object entity);
    }

    interface VersionedRepository {
        @FunctionVersion(3)
        Optional<Object> findById(Long id);

        @FunctionVersion
        void save(Object entity);
    }
}
