package net.unit8.tieto.generator.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.unit8.tieto.generator.parser.GeneratorException;

import java.io.IOException;
import java.net.http.HttpRequest;

/**
 * AI provider implementation using the OpenAI API.
 */
public class OpenAiProvider extends AbstractHttpAiProvider {

    private static final String DEFAULT_API_URL = "https://api.openai.com/v1/chat/completions";

    // A dated snapshot, not the floating "gpt-4o" alias, so regenerations are
    // reproducible (the alias is re-pointed by the vendor over time).
    private static final String DEFAULT_MODEL = "gpt-4o-2024-08-06";

    // Fixed seed makes OpenAI sampling best-effort reproducible across runs.
    private static final int SEED = 0;

    public OpenAiProvider(String apiKey, String model) {
        this(apiKey, model, DEFAULT_MAX_TOKENS, RetrySettings.defaults());
    }

    public OpenAiProvider(String apiKey, String model, int maxTokens) {
        this(apiKey, model, maxTokens, RetrySettings.defaults());
    }

    OpenAiProvider(String apiKey, String model, int maxTokens, RetrySettings retry) {
        this(apiKey, model, DEFAULT_API_URL, maxTokens, retry);
    }

    OpenAiProvider(String apiKey, String model, String apiUrl, int maxTokens) {
        this(apiKey, model, apiUrl, maxTokens, RetrySettings.defaults());
    }

    OpenAiProvider(String apiKey, String model, String apiUrl, int maxTokens, RetrySettings retry) {
        super(apiKey, model != null ? model : DEFAULT_MODEL, apiUrl, maxTokens, retry);
    }

    @Override
    protected String providerName() {
        return "OpenAI";
    }

    @Override
    protected void addAuthHeaders(HttpRequest.Builder builder) {
        builder.header("Authorization", "Bearer " + apiKey);
    }

    @Override
    protected String buildRequestBody(String prompt) throws IOException {
        ObjectNode requestNode = baseRequestNode();
        requestNode.put("seed", SEED);
        var messages = requestNode.putArray("messages");
        var systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", "You are a PostgreSQL expert. Return only SQL, no explanations.");
        var userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        return objectMapper.writeValueAsString(requestNode);
    }

    @Override
    protected String extractSql(JsonNode root) {
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new GeneratorException("Unexpected OpenAI API response: no choices");
        }
        JsonNode choice = choices.get(0);
        checkNotTruncated(choice);

        JsonNode message = choice.get("message");
        JsonNode content = message == null ? null : message.get("content");
        String result = (content == null || content.isNull()) ? "" : content.asText().trim();
        if (result.isEmpty()) {
            // A content-filtered/refusal completion, or an empty/whitespace one: no SQL to
            // deploy. Reject it here (like the Claude path) rather than returning empty SQL.
            throw new GeneratorException(
                    "OpenAI response contained no message content (finish_reason="
                            + choice.path("finish_reason").asText("unknown") + ")");
        }
        return result;
    }

    /**
     * Rejects a completion OpenAI stopped emitting because it hit the token limit
     * ({@code finish_reason == "length"}): the function definition is cut off, so
     * deploying it would install a broken or silently partial function.
     */
    private void checkNotTruncated(JsonNode choice) {
        JsonNode finishReason = choice.get("finish_reason");
        if (finishReason != null && "length".equals(finishReason.asText())) {
            throw truncatedResponse("(finish_reason=length) ");
        }
    }
}
