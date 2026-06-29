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
import java.time.Duration;

/**
 * Base for AI providers that call an HTTP chat API (Claude, OpenAI). Owns the
 * shared request/response machinery — the POST, the non-200 guard, JSON parsing,
 * the deterministic request fields, and SQL post-processing — leaving each
 * provider only the parts that genuinely differ: the endpoint, auth headers,
 * request body shape, and how to read (and truncation-check) the response.
 *
 * <p>A connect and per-request timeout keep a hung connection from blocking the
 * run forever, and transient failures (429, 5xx, network errors) are retried
 * with exponential backoff (honouring {@code Retry-After} when present).</p>
 */
abstract class AbstractHttpAiProvider implements AiProvider {

    // Keep in sync with the --ai-max-tokens default in GenerateCommand (a separate
    // compile-time literal because @Option's defaultValue cannot reference a constant).
    protected static final int DEFAULT_MAX_TOKENS = 8192;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    // Upper bound on any single backoff wait, so neither exponential growth (a
    // large --ai-max-retries) nor a server's Retry-After can stall the run for
    // an unreasonable time, and the duration arithmetic cannot overflow.
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    protected final String apiKey;
    protected final int maxTokens;
    protected final ObjectMapper objectMapper;
    private final String model;
    private final String apiUrl;
    private final RetrySettings retry;
    private final HttpClient httpClient;

    protected AbstractHttpAiProvider(String apiKey, String model, String apiUrl, int maxTokens,
                                     RetrySettings retry) {
        this.apiKey = apiKey;
        this.model = model;
        this.apiUrl = apiUrl;
        this.maxTokens = maxTokens;
        this.retry = retry;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public final GeneratedFunction generateFunction(String prompt) {
        String requestBody;
        try {
            requestBody = buildRequestBody(prompt);
        } catch (IOException e) {
            throw new GeneratorException("Failed to build " + providerName() + " request body", e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(retry.requestTimeout())
                .header("Content-Type", "application/json");
        addAuthHeaders(builder);
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                // Connection/timeout/network error — transient; retry while attempts remain.
                if (attempt < retry.maxRetries()) {
                    sleepBackoff(attempt, null);
                    continue;
                }
                throw new GeneratorException("Failed to call " + providerName() + " API after "
                        + (attempt + 1) + " attempt(s)", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GeneratorException(providerName() + " API call was interrupted", e);
            }

            int status = response.statusCode();
            if (status == 200) {
                return parseResponse(response.body());
            }
            if (isRetryable(status) && attempt < retry.maxRetries()) {
                sleepBackoff(attempt, retryAfter(response));
                continue;
            }
            throw new GeneratorException(
                    providerName() + " API returned status " + status + ": " + response.body());
        }
    }

    private GeneratedFunction parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String sql = ResponseSql.stripMarkdownFences(extractSql(root));
            return new GeneratedFunction(ResponseSql.extractFunctionName(sql), sql, null);
        } catch (IOException e) {
            throw new GeneratorException("Failed to parse " + providerName() + " API response", e);
        }
    }

    /** 429 (rate limit) and 5xx (server) are transient; other 4xx are not. */
    private static boolean isRetryable(int status) {
        return status == 429 || (status >= 500 && status < 600);
    }

    /**
     * The {@code Retry-After} delay in whole seconds, if the response gives a
     * plain numeric one. Values over 9 digits are ignored (the backoff is used
     * instead) so an oversized header cannot overflow the duration arithmetic;
     * the wait is capped at {@link #MAX_BACKOFF} regardless.
     */
    private static Duration retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(String::trim)
                .filter(value -> !value.isEmpty() && value.length() <= 9
                        && value.chars().allMatch(Character::isDigit))
                .map(seconds -> Duration.ofSeconds(Long.parseLong(seconds)))
                .orElse(null);
    }

    private void sleepBackoff(int attempt, Duration retryAfter) {
        Duration delay = retryAfter != null ? retryAfter : exponentialBackoff(attempt);
        if (delay.compareTo(MAX_BACKOFF) > 0) {
            delay = MAX_BACKOFF;
        }
        try {
            Thread.sleep(Math.max(0, delay.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeneratorException(providerName() + " API retry was interrupted", e);
        }
    }

    private Duration exponentialBackoff(int attempt) {
        // Saturate the shift so base * 2^attempt cannot overflow a long; the
        // result is clamped to MAX_BACKOFF anyway, so a saturated exponent is fine.
        int shift = Math.min(attempt, 30);
        return retry.baseBackoff().multipliedBy(1L << shift);
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

    @Override
    public final String provenance() {
        return providerName() + " model=" + model + " temperature=0";
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
