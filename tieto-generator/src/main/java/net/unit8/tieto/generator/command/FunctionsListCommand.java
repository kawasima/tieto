package net.unit8.tieto.generator.command;

import net.unit8.tieto.generator.parser.RepositoryParser;
import net.unit8.tieto.generator.parser.RepositorySpec;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Lists the functions deployed for a repository, classified against its current interface as
 * current, superseded, or orphaned. Read-only.
 */
@Command(name = "list",
        description = "List a repository's deployed functions, classified as current, superseded,"
                + " or orphaned (an old version of a still-declared method, or one whose method is gone).")
public final class FunctionsListCommand implements Callable<Integer> {

    @Option(names = "--source-dir", required = true,
            description = "Root directory of Java sources (to read the current interface)")
    private Path sourceDir;

    @Option(names = "--repository", required = true,
            description = "Fully qualified Repository interface name")
    private String repositoryClassName;

    @Option(names = "--db-url", required = true, description = "JDBC URL for the target database")
    private String dbUrl;

    @Option(names = "--db-user", required = true, description = "Database username")
    private String dbUser;

    @Option(names = "--db-password", interactive = true, arity = "0..1",
            description = "Database password. Prefer the TIETO_DB_PASSWORD environment variable;"
                    + " pass the flag with no value to be prompted (no echo).")
    private String dbPassword;

    @Override
    public Integer call() throws Exception {
        dbPassword = SecretOption.resolve(dbPassword, System.getenv("TIETO_DB_PASSWORD"));
        RepositorySpec repo = new RepositoryParser().parse(sourceDir, repositoryClassName);

        FunctionInventory inventory;
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            inventory = FunctionInventory.of(conn, repo);
        }
        print(inventory);
        return 0;
    }

    private static void print(FunctionInventory inv) {
        System.out.println("Repository: " + inv.repositoryName() + "  (schema " + inv.schema() + ")");
        if (inv.entries().isEmpty()) {
            System.out.println("  no functions deployed under this repository's prefix");
            return;
        }
        printGroup("current", "match a declared method at its @FunctionVersion",
                inv.byStatus(FunctionInventory.Status.CURRENT));
        printGroup("superseded", "an old version of a still-declared method — safe to prune",
                inv.byStatus(FunctionInventory.Status.SUPERSEDED));
        printGroup("orphaned", "no matching method (removed/renamed), or a sibling repository — review before dropping",
                inv.byStatus(FunctionInventory.Status.ORPHANED));
    }

    private static void printGroup(String label, String note, List<FunctionInventory.Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.println("  " + label + " (" + entries.size() + ") — " + note + ":");
        for (FunctionInventory.Entry e : entries) {
            // Unmarked functions are hand-written or predate the ownership marker; prune leaves them.
            String ownership = e.managed() ? "" : "  [unmanaged — not tieto-generated]";
            System.out.println("    " + e.functionName() + "(" + e.identityArgs() + ")" + ownership);
        }
    }
}
