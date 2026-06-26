package net.unit8.tieto.generator.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.unit8.tieto.generator.parser.GeneratorException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Base for AI providers that call an HTTP chat API (Claude, OpenAI). Owns the
 * shared request/response machinery — the POST, the non-200 guard, JSON parsing,
 * the deterministic request fields, and SQL post-processing — leaving each
 * provider only the parts that genuinely differ: the endpoint, auth headers,
 * request body shape, and how to read (and truncation-check) the response.
 */
abstract class AbstractHttpAiProvider implements AiProvider {

    // Keep in sync with the --ai-max-tokens default in GenerateCommand (a separate
    // compile-time literal because @Option's defaultValue cannot reference a constant).
    protected static final int DEFAULT_MAX_TOKENS = 8192;

    protected final String apiKey;
    protected final int maxTokens;
    protected final ObjectMapper objectMapper;
    private final String model;
    private final String apiUrl;
    private final HttpClient httpClient;

    protected AbstractHttpAiProvider(String apiKey, String model, String apiUrl, int maxTokens) {
        this.apiKey = apiKey;
        this.model = model;
        this.apiUrl = apiUrl;
        this.maxTokens = maxTokens;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public final GeneratedFunction generateFunction(String prompt) {
        try {
            String requestBody = buildRequestBody(prompt);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json");
            addAuthHeaders(builder);
            HttpRequest request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new GeneratorException(
                        providerName() + " API returned status " + response.statusCode()
                                + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String sql = ResponseSql.stripMarkdownFences(extractSql(root));
            return new GeneratedFunction(ResponseSql.extractFunctionName(sql), sql, null);
        } catch (IOException | InterruptedException e) {
            throw new GeneratorException("Failed to call " + providerName() + " API", e);
        }
    }

    /**
     * A request body skeleton with the model and the deterministic generation
     * fields ({@code max_tokens}, {@code temperature: 0}); subclasses add their
     * provider-specific {@code messages}.
     */
    protected ObjectNode baseRequestNode() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("model", model);
        node.put("max_tokens", maxTokens);
        // temperature 0 makes generation near-deterministic, minimizing run-to-run drift.
        node.put("temperature", 0);
        return node;
    }

    /**
     * The shared "response truncated at the token limit" error. {@code signalDetail} is
     * the provider's truncation signal rendered for the message (empty, or e.g.
     * {@code "(finish_reason=length) "}), inserted before {@code "at max_tokens"}.
     */
    protected GeneratorException truncatedResponse(String signalDetail) {
        return new GeneratorException(
                providerName() + " response was truncated " + signalDetail
                        + "at max_tokens (" + maxTokens + "); the generated function is incomplete"
                        + " and was not deployed. Simplify the method spec or raise the token limit.");
    }

    /** Human-readable provider name for error messages (e.g. {@code "Claude"}). */
    protected abstract String providerName();

    /** Adds the provider's authentication header(s) to the request. */
    protected abstract void addAuthHeaders(HttpRequest.Builder builder);

    /** Serializes the request body for the prompt. */
    protected abstract String buildRequestBody(String prompt) throws IOException;

    /**
     * Reads the generated SQL text from the parsed response, rejecting a response
     * that was truncated at the token limit or that carries no usable content.
     */
    protected abstract String extractSql(JsonNode root);
}
