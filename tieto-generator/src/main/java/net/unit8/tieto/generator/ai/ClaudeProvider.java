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

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final Pattern FUNCTION_NAME_PATTERN =
            Pattern.compile("CREATE\\s+OR\\s+REPLACE\\s+FUNCTION\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ClaudeProvider(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model != null ? model : "claude-sonnet-4-20250514";
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public GeneratedFunction generateFunction(String prompt) {
        try {
            String requestBody = buildRequestJson(prompt);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
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

            String sql = extractSqlFromResponse(response.body());
            String functionName = extractFunctionName(sql);

            return new GeneratedFunction(functionName, sql, null);
        } catch (IOException | InterruptedException e) {
            throw new GeneratorException("Failed to call Claude API", e);
        }
    }

    private String buildRequestJson(String prompt) throws IOException {
        var requestNode = objectMapper.createObjectNode();
        requestNode.put("model", model);
        requestNode.put("max_tokens", 4096);

        var messages = requestNode.putArray("messages");
        var message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);

        return objectMapper.writeValueAsString(requestNode);
    }

    private String extractSqlFromResponse(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode content = root.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            throw new GeneratorException("Unexpected Claude API response: no content");
        }

        StringBuilder sql = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.get("type").asText())) {
                sql.append(block.get("text").asText());
            }
        }

        String result = sql.toString().trim();
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
