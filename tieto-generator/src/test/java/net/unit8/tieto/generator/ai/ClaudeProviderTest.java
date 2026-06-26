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
 * Drives {@link ClaudeProvider} against a local stub of the Anthropic API so the
 * request it sends (determinism) and how it reacts to a truncated response can be
 * checked without a network call or API key.
 */
class ClaudeProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private volatile String capturedRequestBody;
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ClaudeProvider provider() {
        String url = "http://localhost:" + server.getAddress().getPort() + "/v1/messages";
        return new ClaudeProvider("test-key", "test-model", url, 8192);
    }

    @Test
    void sendsADeterministicRequest() {
        responseBody = completeResponse();

        provider().generateFunction("my prompt");

        JsonNode body = parse(capturedRequestBody);
        assertThat(body.get("temperature").asInt()).isZero();
        assertThat(body.get("max_tokens").asInt()).isEqualTo(8192);
        assertThat(body.get("messages").get(0).get("content").asText()).isEqualTo("my prompt");
    }

    @Test
    void returnsTheFunctionOnACompleteResponse() {
        responseBody = completeResponse();

        GeneratedFunction f = provider().generateFunction("p");

        assertThat(f.functionName()).isEqualTo("foo_v1");
        assertThat(f.sqlBody()).contains("CREATE OR REPLACE FUNCTION foo_v1");
    }

    @Test
    void rejectsAResponseTruncatedAtMaxTokens() {
        responseBody = """
                {"stop_reason":"max_tokens","content":[{"type":"text",
                "text":"CREATE OR REPLACE FUNCTION foo_v1() RETURNS int AS $$ BEGIN"}]}""";

        assertThatThrownBy(() -> provider().generateFunction("p"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("truncat")
                .hasMessageContaining("max_tokens");
    }

    @Test
    void rejectsANonOkStatus() {
        responseStatus = 429;
        responseBody = "{\"error\":\"rate limited\"}";

        assertThatThrownBy(() -> provider().generateFunction("p"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("429");
    }

    @Test
    void rejectsAResponseWithNoTextContent() {
        // A refusal / tool_use response: 200 OK, content array present but no text block.
        responseBody = """
                {"stop_reason":"refusal","content":[{"type":"tool_use","name":"x"}]}""";

        assertThatThrownBy(() -> provider().generateFunction("p"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("no text content");
    }

    private static String completeResponse() {
        return """
                {"stop_reason":"end_turn","content":[{"type":"text",
                "text":"CREATE OR REPLACE FUNCTION foo_v1() RETURNS int AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql;"}]}""";
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
