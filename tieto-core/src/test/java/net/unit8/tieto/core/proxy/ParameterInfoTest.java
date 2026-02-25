package net.unit8.tieto.core.proxy;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ParameterInfoTest {

    @Test
    void from_classifiesPrimitiveAsSimple() throws NoSuchMethodException {
        var method = TestRepo.class.getMethod("findById", Long.class);
        List<ParameterInfo> params = ParameterInfo.from(method);
        assertThat(params).hasSize(1);
        assertThat(params.getFirst().isDomainObject()).isFalse();
        assertThat(params.getFirst().type()).isEqualTo(Long.class);
    }

    @Test
    void from_classifiesStringAsSimple() throws NoSuchMethodException {
        var method = TestRepo.class.getMethod("findByName", String.class);
        List<ParameterInfo> params = ParameterInfo.from(method);
        assertThat(params).hasSize(1);
        assertThat(params.getFirst().isDomainObject()).isFalse();
    }

    @Test
    void from_classifiesUUIDAsSimple() throws NoSuchMethodException {
        var method = TestRepo.class.getMethod("findByUuid", UUID.class);
        List<ParameterInfo> params = ParameterInfo.from(method);
        assertThat(params).hasSize(1);
        assertThat(params.getFirst().isDomainObject()).isFalse();
    }

    @Test
    void from_classifiesEnumAsSimple() throws NoSuchMethodException {
        var method = TestRepo.class.getMethod("findByStatus", Status.class);
        List<ParameterInfo> params = ParameterInfo.from(method);
        assertThat(params).hasSize(1);
        assertThat(params.getFirst().isDomainObject()).isFalse();
    }

    @Test
    void from_classifiesDomainObjectCorrectly() throws NoSuchMethodException {
        var method = TestRepo.class.getMethod("save", Order.class);
        List<ParameterInfo> params = ParameterInfo.from(method);
        assertThat(params).hasSize(1);
        assertThat(params.getFirst().isDomainObject()).isTrue();
        assertThat(params.getFirst().type()).isEqualTo(Order.class);
    }

    @Test
    void from_handlesMixedParameters() throws NoSuchMethodException {
        var method = TestRepo.class.getMethod("updateStatus",
                Long.class, Status.class);
        List<ParameterInfo> params = ParameterInfo.from(method);
        assertThat(params).hasSize(2);
        assertThat(params.get(0).isDomainObject()).isFalse(); // Long
        assertThat(params.get(1).isDomainObject()).isFalse(); // enum
    }

    @Test
    void from_classifiesLocalDateTimeAsSimple() throws NoSuchMethodException {
        var method = TestRepo.class.getMethod("findByDate", LocalDateTime.class);
        List<ParameterInfo> params = ParameterInfo.from(method);
        assertThat(params).hasSize(1);
        assertThat(params.getFirst().isDomainObject()).isFalse();
    }

    // Test types
    record Order(Long id, String name) {}
    enum Status { ACTIVE, INACTIVE }

    interface TestRepo {
        void findById(Long id);
        void findByName(String name);
        void findByUuid(UUID uuid);
        void findByStatus(Status status);
        void findByDate(LocalDateTime date);
        void save(Order order);
        void updateStatus(Long id, Status status);
    }
}
