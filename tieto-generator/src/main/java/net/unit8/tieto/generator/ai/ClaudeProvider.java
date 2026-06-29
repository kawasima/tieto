package net.unit8.tieto.generator.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.unit8.tieto.generator.parser.GeneratorException;

import java.io.IOException;
import java.net.http.HttpRequest;

/**
 * AI provider implementation using the Anthropic Claude API.
 */
public class ClaudeProvider extends AbstractHttpAiProvider {

    private static final String DEFAULT_API_URL = "https://api.anthropic.com/v1/messages";

    public ClaudeProvider(String apiKey, String model) {
        this(apiKey, model, DEFAULT_MAX_TOKENS, RetrySettings.defaults());
    }

    public ClaudeProvider(String apiKey, String model, int maxTokens) {
        this(apiKey, model, maxTokens, RetrySettings.defaults());
    }

    ClaudeProvider(String apiKey, String model, int maxTokens, RetrySettings retry) {
        this(apiKey, model, DEFAULT_API_URL, maxTokens, retry);
    }

    ClaudeProvider(String apiKey, String model, String apiUrl, int maxTokens) {
        this(apiKey, model, apiUrl, maxTokens, RetrySettings.defaults());
    }

    ClaudeProvider(String apiKey, String model, String apiUrl, int maxTokens, RetrySettings retry) {
        super(apiKey, model != null ? model : "claude-sonnet-4-20250514", apiUrl, maxTokens, retry);
    }

    @Override
    protected String providerName() {
        return "Claude";
    }

    @Override
    protected void addAuthHeaders(HttpRequest.Builder builder) {
        builder.header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01");
    }

    @Override
    protected String buildRequestBody(String prompt) throws IOException {
        ObjectNode requestNode = baseRequestNode();
        var messages = requestNode.putArray("messages");
        var message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);
        return objectMapper.writeValueAsString(requestNode);
    }

    @Override
    protected String extractSql(JsonNode root) {
        checkNotTruncated(root);

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
        return result;
    }

    /**
     * Rejects a response Claude stopped emitting because it hit {@code max_tokens}: the
     * function definition is cut off mid-stream, so deploying it would install a broken
     * (or, worse, silently partial) function.
     */
    private void checkNotTruncated(JsonNode root) {
        JsonNode stopReason = root.get("stop_reason");
        if (stopReason != null && "max_tokens".equals(stopReason.asText())) {
            throw truncatedResponse("");
        }
    }
}
