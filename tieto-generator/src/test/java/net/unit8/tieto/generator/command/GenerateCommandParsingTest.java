package net.unit8.tieto.generator.command;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThatCode;

class GenerateCommandParsingTest {

    @Test
    void buildsTheCommandAndDoesNotRequireDbPasswordOnArgv() {
        // Constructing the CommandLine validates the option spec (interactive + arity),
        // and parsing without --db-password confirms it is no longer required (it can
        // come from TIETO_DB_PASSWORD or an interactive prompt instead).
        CommandLine cmd = new CommandLine(new GenerateCommand());

        assertThatCode(() -> cmd.parseArgs(
                "--source-dir", "src/main/java",
                "--repository", "com.example.OrderRepository",
                "--db-url", "jdbc:postgresql://localhost:5432/db",
                "--db-user", "tieto"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAnOverriddenAiMaxTokens() {
        CommandLine cmd = new CommandLine(new GenerateCommand());

        assertThatCode(() -> cmd.parseArgs(
                "--source-dir", "src/main/java",
                "--repository", "com.example.OrderRepository",
                "--db-url", "jdbc:postgresql://localhost:5432/db",
                "--db-user", "tieto",
                "--ai-max-tokens", "16384"))
                .doesNotThrowAnyException();
    }
}
