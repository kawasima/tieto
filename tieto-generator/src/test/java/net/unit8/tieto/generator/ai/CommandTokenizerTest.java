package net.unit8.tieto.generator.ai;

import net.unit8.tieto.generator.parser.GeneratorException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandTokenizerTest {

    @Test
    void splitsOnWhitespaceLikeTheOldBehaviour() {
        assertThat(CommandTokenizer.tokenize("ollama run codellama"))
                .containsExactly("ollama", "run", "codellama");
    }

    @Test
    void keepsASingleQuotedArgumentTogether() {
        assertThat(CommandTokenizer.tokenize("claude --print 'be very concise'"))
                .containsExactly("claude", "--print", "be very concise");
    }

    @Test
    void keepsADoubleQuotedArgumentTogether() {
        assertThat(CommandTokenizer.tokenize("sh -c \"echo a b\""))
                .containsExactly("sh", "-c", "echo a b");
    }

    @Test
    void allowsQuotingPartOfAToken() {
        assertThat(CommandTokenizer.tokenize("--opt=\"a b\""))
                .containsExactly("--opt=a b");
    }

    @Test
    void collapsesRepeatedWhitespace() {
        assertThat(CommandTokenizer.tokenize("  a   b  ")).containsExactly("a", "b");
    }

    @Test
    void handlesBackslashEscapes() {
        assertThat(CommandTokenizer.tokenize("claude --print don\\'t be terse"))
                .containsExactly("claude", "--print", "don't", "be", "terse");
    }

    @Test
    void rejectsAnUnbalancedQuote() {
        assertThatThrownBy(() -> CommandTokenizer.tokenize("claude --print 'oops"))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("quote");
    }

    @Test
    void rejectsAnEmptyCommand() {
        assertThatThrownBy(() -> CommandTokenizer.tokenize("   "))
                .isInstanceOf(GeneratorException.class);
    }
}
