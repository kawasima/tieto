package net.unit8.tieto.generator.ai;

import net.unit8.tieto.generator.parser.GeneratorException;

import java.time.Duration;
import java.util.List;

/**
 * Factory for creating AI provider instances based on configuration.
 */
public class AiProviderFactory {

    // Same dated snapshot the HTTP ClaudeProvider pins, for a reproducible default.
    private static final String DEFAULT_CLAUDE_CLI_MODEL = "claude-sonnet-4-20250514";

    private AiProviderFactory() {}

    /**
     * Creates an AI provider based on the provider name.
     *
     * @param provider the provider name ("claude", "anthropic", "openai", "claude-cli")
     * @param apiKey the API key (nullable for CLI providers)
     * @param model the model override (nullable)
     * @param maxTokens the output token limit for API providers
     * @param requestTimeoutSeconds per-request timeout for HTTP providers
     * @param maxRetries retries on transient (429/5xx/network) failures for HTTP providers
     * @return the AI provider instance
     */
    public static AiProvider create(String provider, String apiKey, String model, int maxTokens,
                                    int requestTimeoutSeconds, int maxRetries) {
        RetrySettings retry = new RetrySettings(
                Duration.ofSeconds(requestTimeoutSeconds), maxRetries, Duration.ofSeconds(1));
        return switch (provider.toLowerCase()) {
            case "claude", "anthropic" -> new ClaudeProvider(apiKey, model, maxTokens, retry);
            case "openai" -> new OpenAiProvider(apiKey, model, maxTokens, retry);
            // Pin the model on the recommended default path too, so it does not
            // silently follow whatever the CLI defaults to. Override with --ai-command.
            case "claude-cli" -> new CliAiProvider(List.of(
                    "claude", "--print",
                    "--model", model != null ? model : DEFAULT_CLAUDE_CLI_MODEL));
            default -> throw new GeneratorException("Unknown AI provider: " + provider);
        };
    }

    /**
     * Creates a CLI-based AI provider from a command string.
     *
     * @param command the shell command (e.g. "ollama run codellama")
     * @return a CliAiProvider that invokes the given command
     */
    public static AiProvider createFromCommand(String command) {
        return new CliAiProvider(CommandTokenizer.tokenize(command));
    }
}
