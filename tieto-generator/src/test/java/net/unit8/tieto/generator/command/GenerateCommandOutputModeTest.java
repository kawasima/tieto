package net.unit8.tieto.generator.command;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An unknown {@code --output-mode} is rejected up front, before any DB or AI work — a typo
 * must not silently skip the {@code --yes} gate and fall through to file output.
 */
class GenerateCommandOutputModeTest {

    private int run(ByteArrayOutputStream err, String... extraArgs) throws Exception {
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

        PrintStream original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            return command.call();
        } finally {
            System.setErr(original);
        }
    }

    @Test
    void unknownOutputModeIsRejectedWithAClearError() throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = run(err, "--output-mode", "Deploy");   // wrong casing, a common typo

        assertThat(code).as("non-zero exit").isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .contains("Unknown --output-mode 'Deploy'")
                .contains("'deploy'")
                .contains("'file'");
    }

    @Test
    void resolvesToFileByDefaultAndToDeployOnlyWithYes() {
        // The safe default is file; --yes selects deploy; an explicit mode always wins.
        assertThat(GenerateCommand.resolveOutputMode(null, false)).isEqualTo("file");
        assertThat(GenerateCommand.resolveOutputMode(null, true)).isEqualTo("deploy");
        assertThat(GenerateCommand.resolveOutputMode("file", true)).isEqualTo("file");
        assertThat(GenerateCommand.resolveOutputMode("deploy", false)).isEqualTo("deploy");
    }
}
