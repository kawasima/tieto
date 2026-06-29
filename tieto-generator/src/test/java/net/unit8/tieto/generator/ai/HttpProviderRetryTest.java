package net.unit8.tieto.generator.ai;

import com.sun.net.httpserver.HttpServer;
import net.unit8.tieto.generator.parser.GeneratorException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the retry/backoff behaviour shared by the HTTP AI providers against a
 * local stub that scripts a status per request. Backoff is ~1ms so the tests
 * stay fast.
 */
class HttpProviderRetryTest {

    private static final String OK_BODY = """
            {"stop_reason":"end_turn","content":[{"type":"text",
            "text":"CREATE OR REPLACE FUNCTION foo_v1() RETURNS int AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql;"}]}""";

    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    /** Status returned for requests 1..N; the last entry repeats for any further request. */
    private volatile int[] statusScript = {200};
    private volatile String retryAfterHeader;

    @BeforeEach
    void startServer() throws IOException {
        requestCount.set(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            int n = requestCount.incrementAndGet();
            int status = statusScript[Math.min(n - 1, statusScript.length - 1)];
            String body = status == 200 ? OK_BODY : "{\"error\":\"transient\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (retryAfterHeader != null && status != 200) {
                exchange.getResponseHeaders().add("Retry-After", retryAfterHeader);
            }
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ClaudeProvider provider(int maxRetries) {
        String url = "http://localhost:" + server.getAddress().getPort() + "/v1/messages";
        return new ClaudeProvider("k", "m", url, 8192,
                new RetrySettings(Duration.ofSeconds(5), maxRetries, Duration.ofMillis(1)));
    }

    @Test
    void retriesOn429ThenSucceeds() {
        statusScript = new int[]{429, 200};

        var result = provider(2).generateFunction("p");

        assertThat(result.functionName()).isEqualTo("foo_v1");
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void retriesOn503ThenSucceeds() {
        statusScript = new int[]{503, 503, 200};

        provider(2).generateFunction("p");

        assertThat(requestCount.get()).isEqualTo(3);
    }

    @Test
    void exhaustsRetriesAndFailsOnPersistent500() {
        statusScript = new int[]{500};

        assertThatThrownBy(() -> provider(2).generateFunction("p"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("500");

        assertThat(requestCount.get()).as("first attempt + 2 retries").isEqualTo(3);
    }

    @Test
    void doesNotRetryOnNonTransient4xx() {
        statusScript = new int[]{400};

        assertThatThrownBy(() -> provider(2).generateFunction("p"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("400");

        assertThat(requestCount.get()).as("400 is not retried").isEqualTo(1);
    }

    @Test
    void honoursRetryAfterHeader() {
        statusScript = new int[]{429, 200};
        retryAfterHeader = "0"; // seconds; 0 keeps the test instant while exercising the header path

        provider(2).generateFunction("p");

        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void oversizedRetryAfterIsIgnoredAndStillRetries() {
        statusScript = new int[]{429, 200};
        retryAfterHeader = "999999999999"; // 12 digits — rejected, falls back to backoff, no crash

        provider(2).generateFunction("p");

        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void zeroRetriesFailsOnFirstTransientError() {
        statusScript = new int[]{429};

        assertThatThrownBy(() -> provider(0).generateFunction("p"))
                .isInstanceOf(GeneratorException.class);

        assertThat(requestCount.get()).isEqualTo(1);
    }
}
