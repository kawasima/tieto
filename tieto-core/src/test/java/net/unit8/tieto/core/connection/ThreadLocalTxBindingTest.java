package net.unit8.tieto.core.connection;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the {@link ThreadLocalTxBinding} lifecycle: a Connection is visible to
 * {@link TxBinding#current()} only for the dynamic extent of the work, the binding
 * is removed even when the work throws, and it does not leak to other threads.
 */
class ThreadLocalTxBindingTest {

    private final ThreadLocalTxBinding binding = new ThreadLocalTxBinding();

    private static Connection dummyConnection() {
        return (Connection) Proxy.newProxyInstance(
                ThreadLocalTxBindingTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> null);
    }

    @Test
    void currentIsNullWhenNothingIsBound() {
        assertThat(binding.current()).isNull();
    }

    @Test
    void exposesTheConnectionDuringWorkAndClearsItAfter() {
        Connection connection = dummyConnection();

        String result = binding.runBound(connection, () -> {
            assertThat(binding.current()).isSameAs(connection);
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(binding.current()).isNull();
    }

    @Test
    void clearsTheBindingEvenWhenTheWorkThrows() {
        Connection connection = dummyConnection();

        assertThatThrownBy(() -> binding.runBound(connection, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(binding.current()).isNull();
    }

    @Test
    void doesNotLeakTheBindingToOtherThreads() {
        Connection connection = dummyConnection();
        AtomicBoolean otherThreadSawBinding = new AtomicBoolean(true);

        binding.runBound(connection, () -> {
            Thread other = new Thread(() -> otherThreadSawBinding.set(binding.current() != null));
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });

        assertThat(otherThreadSawBinding).isFalse();
    }
}
