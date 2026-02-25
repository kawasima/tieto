package net.unit8.tieto.generator;

import net.unit8.tieto.generator.command.GenerateCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Main entry point for the tieto function generator CLI.
 */
@Command(name = "tieto",
        mixinStandardHelpOptions = true,
        version = "0.1.0",
        description = "Generate PostgreSQL Functions from Repository interfaces",
        subcommands = {
                GenerateCommand.class
        })
public class TietoGeneratorCli implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new TietoGeneratorCli()).execute(args);
        System.exit(exitCode);
    }
}
