package net.unit8.tieto.generator.ai;

import net.unit8.tieto.generator.parser.GeneratorException;

import java.util.Arrays;
import java.util.List;

/**
 * Factory for creating AI provider instances based on configuration.
 */
public class AiProviderFactory {

    private AiProviderFactory() {}

    /**
     * Creates an AI provider based on the provider name.
     *
     * @param provider the provider name ("claude", "anthropic", "openai", "claude-cli")
     * @param apiKey the API key (nullable for CLI providers)
     * @param model the model override (nullable)
     * @return the AI provider instance
     */
    public static AiProvider create(String provider, String apiKey, String model) {
        return switch (provider.toLowerCase()) {
            case "claude", "anthropic" -> new ClaudeProvider(apiKey, model);
            case "openai" -> new OpenAiProvider(apiKey, model);
            case "claude-cli" -> new CliAiProvider(List.of("claude", "--print"));
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
        List<String> parts = Arrays.asList(command.split("\\s+"));
        return new CliAiProvider(parts);
    }
}
