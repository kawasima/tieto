package net.unit8.tieto.generator.ai;

import net.unit8.tieto.generator.parser.GeneratorException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        // stdin, stdout and stderr are pumped on separate threads: a single-threaded
        // read-then-read would deadlock if the child fills the stderr pipe while we
        // block reading stdout (or fills stdout while we block writing a large prompt).
        ExecutorService pool = Executors.newFixedThreadPool(3);
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            Process started = process;

            pool.submit(() -> {
                try (OutputStream os = started.getOutputStream()) {
                    os.write(prompt.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // The command may exit before consuming all of stdin (broken pipe);
                    // its exit code / stdout is what we judge the run by.
                }
                return null;
            });
            Future<byte[]> stdout = pool.submit(() -> started.getInputStream().readAllBytes());
            Future<byte[]> stderr = pool.submit(() -> started.getErrorStream().readAllBytes());

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new GeneratorException(
                        "CLI command timed out after " + timeoutSeconds + " seconds: "
                                + String.join(" ", command));
            }

            // The process has exited, so both pipes are at EOF and these complete promptly.
            String output = new String(stdout.get(), StandardCharsets.UTF_8);
            String errorOutput = new String(stderr.get(), StandardCharsets.UTF_8);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new GeneratorException(
                        "CLI command exited with code " + exitCode + ": "
                                + String.join(" ", command)
                                + (errorOutput.isEmpty() ? "" : "\nstderr: " + errorOutput));
            }

            String sql = stripMarkdownFences(output.trim());
            return new GeneratedFunction(extractFunctionName(sql), sql, null);
        } catch (IOException e) {
            throw new GeneratorException(
                    "Failed to execute CLI command: " + String.join(" ", command), e);
        } catch (ExecutionException e) {
            throw new GeneratorException(
                    "Failed to read output of CLI command: " + String.join(" ", command),
                    e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeneratorException("CLI command was interrupted", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            pool.shutdownNow();
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
