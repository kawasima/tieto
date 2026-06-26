package net.unit8.tieto.generator.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.unit8.tieto.generator.parser.GeneratorException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI provider implementation using the Anthropic Claude API.
 */
public class ClaudeProvider implements AiProvider {

    private static final Pattern FUNCTION_NAME_PATTERN =
            Pattern.compile("CREATE\\s+OR\\s+REPLACE\\s+FUNCTION\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

    private static final String DEFAULT_API_URL = "https://api.anthropic.com/v1/messages";
    private static final int DEFAULT_MAX_TOKENS = 8192;

    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final int maxTokens;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ClaudeProvider(String apiKey, String model) {
        this(apiKey, model, DEFAULT_API_URL, DEFAULT_MAX_TOKENS);
    }

    public ClaudeProvider(String apiKey, String model, int maxTokens) {
        this(apiKey, model, DEFAULT_API_URL, maxTokens);
    }

    ClaudeProvider(String apiKey, String model, String apiUrl, int maxTokens) {
        this.apiKey = apiKey;
        this.model = model != null ? model : "claude-sonnet-4-20250514";
        this.apiUrl = apiUrl;
        this.maxTokens = maxTokens;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public GeneratedFunction generateFunction(String prompt) {
        try {
            String requestBody = buildRequestJson(prompt);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new GeneratorException(
                        "Claude API returned status " + response.statusCode()
                                + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            checkNotTruncated(root);

            String sql = extractSqlFromResponse(root);
            String functionName = extractFunctionName(sql);

            return new GeneratedFunction(functionName, sql, null);
        } catch (IOException | InterruptedException e) {
            throw new GeneratorException("Failed to call Claude API", e);
        }
    }

    /**
     * Rejects a response Claude stopped emitting because it hit {@code max_tokens}: the
     * function definition is cut off mid-stream, so deploying it would install a broken
     * (or, worse, silently partial) function.
     */
    private void checkNotTruncated(JsonNode root) {
        JsonNode stopReason = root.get("stop_reason");
        if (stopReason != null && "max_tokens".equals(stopReason.asText())) {
            throw new GeneratorException(
                    "Claude response was truncated at max_tokens (" + maxTokens
                            + "); the generated function is incomplete and was not deployed."
                            + " Simplify the method spec or raise the token limit.");
        }
    }

    private String buildRequestJson(String prompt) throws IOException {
        var requestNode = objectMapper.createObjectNode();
        requestNode.put("model", model);
        requestNode.put("max_tokens", maxTokens);
        // temperature 0 makes generation near-deterministic, minimizing run-to-run drift.
        requestNode.put("temperature", 0);

        var messages = requestNode.putArray("messages");
        var message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);

        return objectMapper.writeValueAsString(requestNode);
    }

    private String extractSqlFromResponse(JsonNode root) {
        JsonNode content = root.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            throw new GeneratorException("Unexpected Claude API response: no content");
        }

        StringBuilder sql = new StringBuilder();
        for (JsonNode block : content) {
            JsonNode type = block.get("type");
            if (type != null && "text".equals(type.asText())) {
                JsonNode text = block.get("text");
                if (text != null) {
                    sql.append(text.asText());
                }
            }
        }

        String result = sql.toString().trim();
        if (result.isEmpty()) {
            // No text block (e.g. a refusal or tool_use response): there is no SQL to deploy.
            throw new GeneratorException(
                    "Claude response contained no text content (stop_reason="
                            + root.path("stop_reason").asText("unknown") + ")");
        }
        // Strip markdown code fences if present
        if (result.startsWith("```")) {
            result = result.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
        }
        return result;
    }

    private String extractFunctionName(String sql) {
        Matcher matcher = FUNCTION_NAME_PATTERN.matcher(sql);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "unknown_function";
    }
}
