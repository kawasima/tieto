package net.unit8.tieto.core.proxy;

import net.unit8.tieto.core.exception.FunctionCallException;
import net.unit8.tieto.core.mapper.MapperRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReturnTypeHandlerTest {

    private final MapperRegistry registry = MapperRegistry.builder().build();

    @Test
    void listHandler_mapsEveryRowAndIsUnmodifiable() throws Exception {
        var handler = new ReturnTypeHandler.ListHandler(TestEntity.class);

        Object result = handler.extractResult(
                resultSetOf("{\"id\":1,\"name\":\"a\"}", "{\"id\":2,\"name\":\"b\"}"), registry);

        assertThat(result).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(TestEntity.class))
                .containsExactly(new TestEntity(1L, "a"), new TestEntity(2L, "b"));
        assertThatThrownBy(() -> ((List<?>) result).add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void listHandler_returnsEmptyListForNoRows() throws Exception {
        var handler = new ReturnTypeHandler.ListHandler(TestEntity.class);
        assertThat((List<?>) handler.extractResult(resultSetOf(), registry)).isEmpty();
    }

    @Test
    void listHandler_throwsOnASqlNullRowRatherThanDroppingIt() {
        var handler = new ReturnTypeHandler.ListHandler(TestEntity.class);
        assertThatThrownBy(() -> handler.extractResult(
                resultSetOf("{\"id\":1,\"name\":\"a\"}", (String) null), registry))
                .isInstanceOf(FunctionCallException.class)
                .hasMessageContaining("SQL NULL row");
    }

    @Test
    void singleHandler_throwsWhenNoRow() {
        var handler = new ReturnTypeHandler.SingleHandler(TestEntity.class);
        assertThatThrownBy(() -> handler.extractResult(resultSetOf(), registry))
                .isInstanceOf(FunctionCallException.class)
                .hasMessageContaining("got none");
    }

    @Test
    void singleHandler_throwsWhenJsonIsNull() {
        var handler = new ReturnTypeHandler.SingleHandler(TestEntity.class);
        assertThatThrownBy(() -> handler.extractResult(resultSetOf((String) null), registry))
                .isInstanceOf(FunctionCallException.class)
                .hasMessageContaining("non-null");
    }

    @Test
    void singleHandler_throwsWhenMoreThanOneRow() {
        var handler = new ReturnTypeHandler.SingleHandler(TestEntity.class);
        assertThatThrownBy(() -> handler.extractResult(
                resultSetOf("{\"id\":1,\"name\":\"a\"}", "{\"id\":2,\"name\":\"b\"}"), registry))
                .isInstanceOf(FunctionCallException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    void optionalHandler_emptyWhenNoRow() throws Exception {
        var handler = new ReturnTypeHandler.OptionalHandler(TestEntity.class);
        assertThat(handler.extractResult(resultSetOf(), registry)).isEqualTo(Optional.empty());
    }

    @Test
    void optionalHandler_emptyWhenJsonIsNull() throws Exception {
        var handler = new ReturnTypeHandler.OptionalHandler(TestEntity.class);
        assertThat(handler.extractResult(resultSetOf((String) null), registry)).isEqualTo(Optional.empty());
    }

    @Test
    void optionalHandler_throwsWhenMoreThanOneRow() {
        var handler = new ReturnTypeHandler.OptionalHandler(TestEntity.class);
        assertThatThrownBy(() -> handler.extractResult(
                resultSetOf("{\"id\":1,\"name\":\"a\"}", "{\"id\":2,\"name\":\"b\"}"), registry))
                .isInstanceOf(FunctionCallException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    void voidHandler_returnsNull() throws Exception {
        assertThat(new ReturnTypeHandler.VoidHandler().extractResult(resultSetOf(), registry)).isNull();
    }

    /** A single-column {@link ResultSet} stub whose rows are the given JSON strings (null allowed). */
    private static ResultSet resultSetOf(String... rows) {
        int[] cursor = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                ReturnTypeHandlerTest.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> ++cursor[0] < rows.length;
                    case "getString" -> rows[cursor[0]];
                    case "close" -> null;
                    default -> defaultValue(method);
                });
    }

    private static Object defaultValue(Method method) {
        Class<?> r = method.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        return null;
    }

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

    @Test
    void from_rejectsNestedGenericWithAClearError() throws NoSuchMethodException {
        var method = TestRepo.class.getMethod("nestedGeneric");
        assertThatThrownBy(() -> ReturnTypeHandler.from(method))
                .isInstanceOf(FunctionCallException.class)
                .hasMessageContaining("Unsupported return type");
    }

    @Test
    void from_rejectsWildcardElementWithAClearError() throws NoSuchMethodException {
        var method = TestRepo.class.getMethod("wildcard");
        assertThatThrownBy(() -> ReturnTypeHandler.from(method))
                .isInstanceOf(FunctionCallException.class)
                .hasMessageContaining("Unsupported return type");
    }

    // Test types
    record TestEntity(Long id, String name) {}

    interface TestRepo {
        List<TestEntity> findAll();
        Optional<TestEntity> findById(Long id);
        TestEntity getById(Long id);
        void save(TestEntity entity);
        List<Optional<TestEntity>> nestedGeneric();
        List<? extends TestEntity> wildcard();
    }
}
