package net.unit8.tieto.generator.ai;

import net.unit8.tieto.generator.parser.GeneratorException;

/**
 * Factory for creating AI provider instances based on configuration.
 */
public class AiProviderFactory {

    private AiProviderFactory() {}

    /**
     * Creates an AI provider based on the provider name.
     *
     * @param provider the provider name ("claude", "anthropic", "openai")
     * @param apiKey the API key
     * @param model the model override (nullable)
     * @return the AI provider instance
     */
    public static AiProvider create(String provider, String apiKey, String model) {
        return switch (provider.toLowerCase()) {
            case "claude", "anthropic" -> new ClaudeProvider(apiKey, model);
            case "openai" -> new OpenAiProvider(apiKey, model);
            default -> throw new GeneratorException("Unknown AI provider: " + provider);
        };
    }
}
