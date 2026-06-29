package net.unit8.tieto.generator.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiProviderFactoryTest {

    @Test
    void claudeCliPinsTheModelOnTheDefaultPath() {
        AiProvider provider = AiProviderFactory.create("claude-cli", null, null, 8192, 120, 2);

        assertThat(provider).isInstanceOf(CliAiProvider.class);
        assertThat(((CliAiProvider) provider).command())
                .containsSequence("--model", "claude-sonnet-4-20250514");
    }

    @Test
    void claudeCliHonoursAnExplicitModelOverride() {
        AiProvider provider = AiProviderFactory.create("claude-cli", null, "sonnet", 8192, 120, 2);

        assertThat(((CliAiProvider) provider).command())
                .containsSequence("--model", "sonnet");
    }

    @Test
    void httpProvidersReportProvenanceWithTheModel() {
        AiProvider claude = AiProviderFactory.create("claude", "key", null, 8192, 120, 2);
        AiProvider openai = AiProviderFactory.create("openai", "key", null, 8192, 120, 2);

        assertThat(claude.provenance()).contains("Claude", "claude-sonnet-4-20250514");
        assertThat(openai.provenance()).contains("OpenAI", "gpt-4o-2024-08-06");
    }
}
