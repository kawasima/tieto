package net.unit8.tieto.core.proxy;

import net.unit8.tieto.core.StubDataSource;
import net.unit8.tieto.core.function.DefaultFunctionNameResolver;
import net.unit8.tieto.core.function.InvocationConfig;
import net.unit8.tieto.core.mapper.MapperRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link RepositoryInvocationHandler}'s non-database dispatch: the
 * {@link Object} methods and default interface methods that must be handled
 * without ever touching the DataSource. (The function-invocation path is covered
 * by the connection/function integration tests.)
 */
class RepositoryInvocationHandlerTest {

    interface SampleRepository {
        default String greeting() {
            return "hello";
        }
    }

    private SampleRepository newProxy() {
        var handler = new RepositoryInvocationHandler(
                SampleRepository.class,
                new StubDataSource(),
                MapperRegistry.builder().build(),
                new DefaultFunctionNameResolver(),
                InvocationConfig.fromSystemProperties());
        return (SampleRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{SampleRepository.class},
                handler);
    }

    @Test
    void toStringNamesTheProxiedInterface() {
        assertThat(newProxy().toString()).isEqualTo("SampleRepository@tieto-proxy");
    }

    @Test
    void hashCodeIsIdentityBased() {
        SampleRepository proxy = newProxy();
        assertThat(proxy.hashCode()).isEqualTo(System.identityHashCode(proxy));
    }

    @Test
    void equalsIsReferenceIdentity() {
        SampleRepository one = newProxy();
        SampleRepository another = newProxy();
        assertThat(one).isEqualTo(one);
        assertThat(one).isNotEqualTo(another);
    }

    @Test
    void defaultMethodsRunOnTheProxyWithoutHittingTheDataSource() {
        assertThat(newProxy().greeting()).isEqualTo("hello");
    }
}
