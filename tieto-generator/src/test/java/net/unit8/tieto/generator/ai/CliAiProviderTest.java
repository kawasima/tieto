package net.unit8.tieto.generator.ai;

import net.unit8.tieto.generator.parser.GeneratorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@link CliAiProvider} against real {@code sh -c} processes.
 */
class CliAiProviderTest {

    private static CliAiProvider sh(String script) {
        return new CliAiProvider(List.of("sh", "-c", script));
    }

    @Test
    void readsTheGeneratedFunctionFromStdout() {
        var provider = sh("echo 'CREATE OR REPLACE FUNCTION foo_v1() RETURNS int AS x'");
        GeneratedFunction f = provider.generateFunction("a prompt");
        assertThat(f.functionName()).isEqualTo("foo_v1");
        assertThat(f.sqlBody()).contains("CREATE OR REPLACE FUNCTION foo_v1");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void doesNotDeadlockWhenTheCommandFloodsStderr() {
        // ~100KB to stderr (more than the pipe buffer) before a function on stdout.
        // A read-stdout-then-stderr implementation would deadlock here.
        var provider = sh("yes x | head -n 50000 1>&2; echo 'CREATE OR REPLACE FUNCTION ok_v1()'");
        assertThat(provider.generateFunction("a prompt").functionName()).isEqualTo("ok_v1");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void timesOutAHangingCommand() {
        var provider = new CliAiProvider(List.of("sh", "-c", "sleep 60"), 1);
        assertThatThrownBy(() -> provider.generateFunction("a prompt"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void doesNotHangWhenABackgroundChildHoldsTheOutputPipe() {
        // sh exits immediately, but the backgrounded sleep inherits and holds stdout,
        // so readAllBytes never sees EOF. The bounded get must fail instead of hanging.
        var provider = new CliAiProvider(
                List.of("sh", "-c", "sleep 8 & echo 'CREATE OR REPLACE FUNCTION foo_v1()'"), 2);
        assertThatThrownBy(() -> provider.generateFunction("a prompt"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("did not close");
    }

    @Test
    void reportsANonZeroExitWithStderr() {
        var provider = sh("echo 'boom' 1>&2; exit 3");
        assertThatThrownBy(() -> provider.generateFunction("a prompt"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("exited with code 3")
                .hasMessageContaining("boom");
    }
}
