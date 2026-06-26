package net.unit8.tieto.generator.ai;

import net.unit8.tieto.generator.parser.GeneratorException;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an {@code --ai-command} string into argv tokens, respecting single and
 * double quotes so an argument that contains spaces (e.g. {@code --print 'be concise'})
 * stays a single token instead of being shredded on whitespace.
 */
final class CommandTokenizer {

    private CommandTokenizer() {}

    static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        int i = 0;
        int n = command.length();

        while (i < n) {
            char c = command.charAt(i);
            if (c == '\'' || c == '"') {
                inToken = true;
                i++;
                while (i < n && command.charAt(i) != c) {
                    current.append(command.charAt(i));
                    i++;
                }
                if (i >= n) {
                    throw new GeneratorException(
                            "Unbalanced quote in --ai-command: " + command);
                }
                i++;   // consume the closing quote
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
                i++;
            } else {
                current.append(c);
                inToken = true;
                i++;
            }
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        if (tokens.isEmpty()) {
            throw new GeneratorException("--ai-command is empty");
        }
        return tokens;
    }
}
