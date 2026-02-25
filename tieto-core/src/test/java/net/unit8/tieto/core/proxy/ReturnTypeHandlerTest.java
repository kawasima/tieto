package net.unit8.tieto.core.proxy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReturnTypeHandlerTest {

    @Test
    void from_listReturnType() throws NoSuchMethodException {
        var method = TestRepo.class.getMethod("findAll");
        ReturnTypeHandler handler = ReturnTypeHandler.from(method);
        assertThat(handler).isInstanceOf(ReturnTypeHandler.ListHandler.class);
        assertThat(((ReturnTypeHandler.ListHandler) handler).elementType())
                .isEqualTo(TestEntity.class);
    }

    @Test
    void from_optionalReturnType() throws NoSuchMethodException {
        var method = TestRepo.class.getMethod("findById", Long.class);
        ReturnTypeHandler handler = ReturnTypeHandler.from(method);
        assertThat(handler).isInstanceOf(ReturnTypeHandler.OptionalHandler.class);
        assertThat(((ReturnTypeHandler.OptionalHandler) handler).elementType())
                .isEqualTo(TestEntity.class);
    }

    @Test
    void from_singleReturnType() throws NoSuchMethodException {
        var method = TestRepo.class.getMethod("getById", Long.class);
        ReturnTypeHandler handler = ReturnTypeHandler.from(method);
        assertThat(handler).isInstanceOf(ReturnTypeHandler.SingleHandler.class);
        assertThat(((ReturnTypeHandler.SingleHandler) handler).type())
                .isEqualTo(TestEntity.class);
    }

    @Test
    void from_voidReturnType() throws NoSuchMethodException {
        var method = TestRepo.class.getMethod("save", TestEntity.class);
        ReturnTypeHandler handler = ReturnTypeHandler.from(method);
        assertThat(handler).isInstanceOf(ReturnTypeHandler.VoidHandler.class);
    }

    // Test types
    record TestEntity(Long id, String name) {}

    interface TestRepo {
        List<TestEntity> findAll();
        Optional<TestEntity> findById(Long id);
        TestEntity getById(Long id);
        void save(TestEntity entity);
    }
}
