package net.unit8.tieto.generator.command;

import net.unit8.tieto.generator.parser.GeneratorException;

/**
 * Resolves a secret CLI option, preferring an environment variable over a value
 * passed on the command line.
 *
 * <p>Passing a secret as a CLI argument leaks it into the process list
 * ({@code ps} / {@code /proc/<pid>/cmdline}), shell history, and CI logs, so an
 * environment variable (or an interactive prompt) is preferred.</p>
 */
final class SecretOption {

    private SecretOption() {}

    /**
     * The command-line value if present, otherwise the environment value;
     * throws if the secret is required but neither is provided.
     */
    static String require(String cliValue, String envValue, String label, String envVar) {
        String resolved = orNull(cliValue, envValue);
        if (resolved == null) {
            throw new GeneratorException(label + " is required: set the " + envVar
                    + " environment variable (recommended — keeps it out of the process list,"
                    + " shell history, and CI logs) or pass the option on the command line.");
        }
        return resolved;
    }

    /** The command-line value if present, otherwise the environment value, otherwise null. */
    static String orNull(String cliValue, String envValue) {
        if (cliValue != null && !cliValue.isBlank()) {
            return cliValue;
        }
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return null;
    }
}
