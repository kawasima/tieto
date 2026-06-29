package net.unit8.tieto.generator.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import net.unit8.tieto.generator.parser.GeneratorException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@link OpenAiProvider} against a local stub of the OpenAI API so the request
 * it sends (determinism) and how it reacts to a truncated response can be checked
 * without a network call or API key.
 */
class OpenAiProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private volatile String capturedRequestBody;
    private volatile String responseBody = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static final RetrySettings FAST_RETRY =
            new RetrySettings(java.time.Duration.ofSeconds(5), 2, java.time.Duration.ofMillis(1));

    private OpenAiProvider provider() {
        String url = "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions";
        return new OpenAiProvider("test-key", "test-model", url, 8192, FAST_RETRY);
    }

    @Test
    void sendsADeterministicRequest() {
        responseBody = completeResponse();

        provider().generateFunction("my prompt");

        JsonNode body = parse(capturedRequestBody);
        assertThat(body.get("temperature").asInt()).isZero();
        assertThat(body.get("max_tokens").asInt()).isEqualTo(8192);
    }

    @Test
    void returnsTheFunctionOnACompleteResponse() {
        responseBody = completeResponse();

        GeneratedFunction f = provider().generateFunction("p");

        assertThat(f.functionName()).isEqualTo("foo_v1");
    }

    @Test
    void rejectsAResponseTruncatedByLength() {
        responseBody = """
                {"choices":[{"finish_reason":"length","message":{"content":
                "CREATE OR REPLACE FUNCTION foo_v1() RETURNS int AS $$ BEGIN"}}]}""";

        assertThatThrownBy(() -> provider().generateFunction("p"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("truncat")
                .hasMessageContaining("length");
    }

    @Test
    void rejectsAResponseWithNoMessageContent() {
        // A content-filtered / refusal completion: 200 OK but message.content is absent.
        responseBody = """
                {"choices":[{"finish_reason":"content_filter","message":{"role":"assistant"}}]}""";

        assertThatThrownBy(() -> provider().generateFunction("p"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("no message content")
                .hasMessageContaining("content_filter");
    }

    @Test
    void rejectsAResponseWithBlankContent() {
        // 200 OK, finish_reason=stop, but the assistant message is empty/whitespace.
        responseBody = """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"   "}}]}""";

        assertThatThrownBy(() -> provider().generateFunction("p"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("no message content");
    }

    private static String completeResponse() {
        return """
                {"choices":[{"finish_reason":"stop","message":{"content":
                "CREATE OR REPLACE FUNCTION foo_v1() RETURNS int AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql;"}}]}""";
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
