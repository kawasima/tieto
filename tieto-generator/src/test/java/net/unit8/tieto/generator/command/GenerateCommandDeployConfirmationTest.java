package net.unit8.tieto.generator.command;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deploy mode writes AI-generated SQL straight into the live database, so it
 * requires an explicit {@code --yes}. The check runs before any DB or AI work.
 */
class GenerateCommandDeployConfirmationTest {

    private int run(String... extraArgs) throws Exception {
        GenerateCommand command = new GenerateCommand();
        String[] base = {
                "--source-dir", "src/main/java",
                "--repository", "com.example.OrderRepository",
                "--db-url", "jdbc:postgresql://localhost:5432/db",
                "--db-user", "tieto",
        };
        String[] args = new String[base.length + extraArgs.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extraArgs, 0, args, base.length, extraArgs.length);
        new CommandLine(command).parseArgs(args);
        return command.call();
    }

    @Test
    void deployModeWithoutYesIsRefusedBeforeTouchingTheDatabase() throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int code;
        try {
            // Default output mode is deploy; no --yes given.
            code = run();
        } finally {
            System.setErr(original);
        }

        assertThat(code).as("non-zero exit").isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .contains("--yes")
                .contains("--output-mode file");
    }
}
