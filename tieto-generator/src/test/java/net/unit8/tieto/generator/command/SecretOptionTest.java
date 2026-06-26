package net.unit8.tieto.generator.command;

import net.unit8.tieto.generator.parser.GeneratorException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretOptionTest {

    @Test
    void require_prefersTheCommandLineValue() {
        assertThat(SecretOption.require("fromArg", "fromEnv", "Password", "TIETO_DB_PASSWORD"))
                .isEqualTo("fromArg");
    }

    @Test
    void require_fallsBackToTheEnvironmentValue() {
        assertThat(SecretOption.require(null, "fromEnv", "Password", "TIETO_DB_PASSWORD"))
                .isEqualTo("fromEnv");
    }

    @Test
    void require_ignoresABlankCommandLineValue() {
        assertThat(SecretOption.require("   ", "fromEnv", "Password", "TIETO_DB_PASSWORD"))
                .isEqualTo("fromEnv");
    }

    @Test
    void require_throwsWithTheEnvVarNameWhenNeitherIsProvided() {
        assertThatThrownBy(() -> SecretOption.require(null, null, "Database password", "TIETO_DB_PASSWORD"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("TIETO_DB_PASSWORD");
    }

    @Test
    void orNull_returnsNullWhenNeitherIsProvided() {
        assertThat(SecretOption.orNull(null, "")).isNull();
    }

    @Test
    void orNull_prefersCommandLineThenEnv() {
        assertThat(SecretOption.orNull("a", "b")).isEqualTo("a");
        assertThat(SecretOption.orNull(null, "b")).isEqualTo("b");
    }
}
