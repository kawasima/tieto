package net.unit8.tieto.generator.command;

/**
 * Resolves a secret CLI option, preferring a value passed on the command line
 * over an environment variable.
 *
 * <p>Passing a secret as a CLI argument leaks it into the process list
 * ({@code ps} / {@code /proc/<pid>/cmdline}), shell history, and CI logs, so an
 * environment variable (or an interactive prompt) is preferred.</p>
 */
final class SecretOption {

    private SecretOption() {}

    /**
     * The command-line value if it was supplied (even an empty string, e.g. for
     * trust/peer auth), otherwise the environment value, otherwise {@code null}
     * when neither was supplied. Empty is a valid value; only {@code null} means
     * "not supplied".
     */
    static String resolve(String cliValue, String envValue) {
        return cliValue != null ? cliValue : envValue;
    }
}
