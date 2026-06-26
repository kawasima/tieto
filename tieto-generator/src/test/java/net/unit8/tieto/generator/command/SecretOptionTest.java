package net.unit8.tieto.generator.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretOptionTest {

    @Test
    void resolve_prefersTheCommandLineValue() {
        assertThat(SecretOption.resolve("fromArg", "fromEnv")).isEqualTo("fromArg");
    }

    @Test
    void resolve_fallsBackToTheEnvironmentValue() {
        assertThat(SecretOption.resolve(null, "fromEnv")).isEqualTo("fromEnv");
    }

    @Test
    void resolve_treatsAnEmptyCommandLineValueAsSupplied() {
        // An empty password (e.g. trust/peer auth) is a value, not "absent".
        assertThat(SecretOption.resolve("", "fromEnv")).isEqualTo("");
    }

    @Test
    void resolve_returnsNullOnlyWhenNeitherIsSupplied() {
        assertThat(SecretOption.resolve(null, null)).isNull();
    }
}
