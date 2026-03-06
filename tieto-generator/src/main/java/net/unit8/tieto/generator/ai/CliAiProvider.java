package net.unit8.tieto.generator.ai;

import net.unit8.tieto.generator.parser.GeneratorException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI provider that delegates to an external CLI command (e.g. {@code claude --print}).
 *
 * <p>The prompt is written to the process's stdin, and the generated SQL
 * is read from stdout. This allows reuse of the user's existing CLI
 * authentication without managing API keys.</p>
 */
public class CliAiProvider implements AiProvider {

    private static final Pattern FUNCTION_NAME_PATTERN =
            Pattern.compile("CREATE\\s+OR\\s+REPLACE\\s+FUNCTION\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

    private final List<String> command;
    private final long timeoutSeconds;

    public CliAiProvider(List<String> command) {
        this(command, 120);
    }

    public CliAiProvider(List<String> command, long timeoutSeconds) {
        this.command = List.copyOf(command);
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public GeneratedFunction generateFunction(String prompt) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Write prompt to stdin
            try (OutputStream os = process.getOutputStream()) {
                os.write(prompt.getBytes(StandardCharsets.UTF_8));
            }

            // Read stdout
            String output = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            // Read stderr for diagnostics
            String errorOutput = new String(
                    process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new GeneratorException(
                        "CLI command timed out after " + timeoutSeconds + " seconds: "
                                + String.join(" ", command));
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new GeneratorException(
                        "CLI command exited with code " + exitCode + ": "
                                + String.join(" ", command)
                                + (errorOutput.isEmpty() ? "" : "\nstderr: " + errorOutput));
            }

            String sql = stripMarkdownFences(output.trim());
            String functionName = extractFunctionName(sql);

            return new GeneratedFunction(functionName, sql, null);
        } catch (IOException e) {
            throw new GeneratorException(
                    "Failed to execute CLI command: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeneratorException("CLI command was interrupted", e);
        }
    }

    private static String stripMarkdownFences(String text) {
        if (text.startsWith("```")) {
            text = text.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
        }
        return text;
    }

    private static String extractFunctionName(String sql) {
        Matcher matcher = FUNCTION_NAME_PATTERN.matcher(sql);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "unknown_function";
    }
}
