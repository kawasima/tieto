package net.unit8.tieto.generator.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Parent command for inspecting and managing a repository's deployed functions.
 */
@Command(name = "functions",
        description = "Inspect and manage a repository's deployed PostgreSQL functions.",
        subcommands = { FunctionsListCommand.class })
public final class FunctionsCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
