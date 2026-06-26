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
 * AI provider implementation using the OpenAI API.
 */
public class OpenAiProvider implements AiProvider {

    private static final String DEFAULT_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final int DEFAULT_MAX_TOKENS = 8192;
    private static final Pattern FUNCTION_NAME_PATTERN =
            Pattern.compile("CREATE\\s+OR\\s+REPLACE\\s+FUNCTION\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final int maxTokens;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiProvider(String apiKey, String model) {
        this(apiKey, model, DEFAULT_API_URL, DEFAULT_MAX_TOKENS);
    }

    public OpenAiProvider(String apiKey, String model, int maxTokens) {
        this(apiKey, model, DEFAULT_API_URL, maxTokens);
    }

    OpenAiProvider(String apiKey, String model, String apiUrl, int maxTokens) {
        this.apiKey = apiKey;
        this.model = model != null ? model : "gpt-4o";
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
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new GeneratorException(
                        "OpenAI API returned status " + response.statusCode()
                                + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new GeneratorException("Unexpected OpenAI API response: no choices");
            }
            checkNotTruncated(choices.get(0));

            String sql = extractSqlFromChoice(choices.get(0));
            String functionName = extractFunctionName(sql);

            return new GeneratedFunction(functionName, sql, null);
        } catch (IOException | InterruptedException e) {
            throw new GeneratorException("Failed to call OpenAI API", e);
        }
    }

    /**
     * Rejects a completion OpenAI stopped emitting because it hit the token limit
     * ({@code finish_reason == "length"}): the function definition is cut off, so
     * deploying it would install a broken or silently partial function.
     */
    private void checkNotTruncated(JsonNode choice) {
        JsonNode finishReason = choice.get("finish_reason");
        if (finishReason != null && "length".equals(finishReason.asText())) {
            throw new GeneratorException(
                    "OpenAI response was truncated (finish_reason=length) at max_tokens ("
                            + maxTokens + "); the generated function is incomplete and was not"
                            + " deployed. Simplify the method spec or raise the token limit.");
        }
    }

    private String buildRequestJson(String prompt) throws IOException {
        var requestNode = objectMapper.createObjectNode();
        requestNode.put("model", model);
        requestNode.put("max_tokens", maxTokens);
        // Deterministic output: the same spec + schema should yield the same function.
        requestNode.put("temperature", 0);

        var messages = requestNode.putArray("messages");
        var systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", "You are a PostgreSQL expert. Return only SQL, no explanations.");
        var userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);

        return objectMapper.writeValueAsString(requestNode);
    }

    private String extractSqlFromChoice(JsonNode choice) {
        String result = choice.get("message").get("content").asText().trim();
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
