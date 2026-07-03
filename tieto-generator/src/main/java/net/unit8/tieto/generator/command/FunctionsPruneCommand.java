package net.unit8.tieto.generator.command;

import net.unit8.tieto.generator.parser.RepositoryParser;
import net.unit8.tieto.generator.parser.RepositorySpec;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * Drops a repository's superseded (and, opt-in, orphaned) deployed functions. Dry-run by default:
 * it prints what it would drop and changes nothing until {@code --yes} is given.
 */
@Command(name = "prune",
        description = "Drop a repository's superseded function versions (and, with --include-orphaned,"
                + " orphaned ones). Dry-run unless --yes.")
public final class FunctionsPruneCommand implements Callable<Integer> {

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

    @Option(names = {"--yes", "-y"},
            description = "Actually drop the functions. Without it, prune only prints what it would do.")
    private boolean confirm;

    @Option(names = "--keep-last", defaultValue = "0",
            description = "Retain the N most recent superseded versions per method as a rollback"
                    + " cushion (default 0 = drop all superseded).")
    private int keepLast;

    @Option(names = "--include-orphaned",
            description = "Also drop orphaned functions (no matching method). Off by default because"
                    + " an orphan may belong to a sibling repository — review the `functions list`"
                    + " output first.")
    private boolean includeOrphaned;

    @Option(names = "--include-unmanaged",
            description = "Also drop functions that lack tieto's ownership marker (hand-written, or"
                    + " generated before the marker existed). Off by default: prune only removes SQL"
                    + " it can prove it generated. Use this to clean up a pre-marker deployment, after"
                    + " reviewing `functions list`.")
    private boolean includeUnmanaged;

    @Override
    public Integer call() throws Exception {
        dbPassword = SecretOption.resolve(dbPassword, System.getenv("TIETO_DB_PASSWORD"));
        RepositorySpec repo = new RepositoryParser().parse(sourceDir, repositoryClassName);

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            FunctionInventory inventory = FunctionInventory.of(conn, repo);
            List<FunctionInventory.Entry> targets = targets(inventory);

            if (targets.isEmpty()) {
                System.out.println("Nothing to prune for " + repo.simpleName() + ".");
                return 0;
            }

            System.out.println((confirm ? "Dropping" : "Would drop") + " " + targets.size()
                    + " function(s) for " + repo.simpleName() + " (schema " + inventory.schema() + "):");
            for (FunctionInventory.Entry t : targets) {
                System.out.println("  " + t.status().name().toLowerCase() + "  "
                        + t.functionName() + "(" + t.identityArgs() + ")");
            }

            if (!confirm) {
                System.out.println("\nDry run — nothing was dropped. Re-run with --yes to drop these.");
                return 0;
            }
            FunctionPruner.drop(conn, targets);
            System.out.println("\nDropped " + targets.size() + " function(s).");
        }
        return 0;
    }

    /** Superseded functions beyond the {@code --keep-last} cushion, plus orphaned ones if requested. */
    private List<FunctionInventory.Entry> targets(FunctionInventory inventory) {
        List<FunctionInventory.Entry> superseded = inventory.byStatus(FunctionInventory.Status.SUPERSEDED);

        // Per method, keep the highest `keepLast` versions.
        Map<String, TreeSet<Integer>> versionsByMethod = new HashMap<>();
        for (FunctionInventory.Entry e : superseded) {
            versionsByMethod.computeIfAbsent(e.method(), k -> new TreeSet<>(Comparator.reverseOrder()))
                    .add(e.version());
        }
        Set<String> keep = new HashSet<>();
        versionsByMethod.forEach((method, versions) ->
                versions.stream().limit(Math.max(0, keepLast)).forEach(v -> keep.add(method + ":" + v)));

        List<FunctionInventory.Entry> targets = new ArrayList<>(superseded.stream()
                .filter(e -> !keep.contains(e.method() + ":" + e.version()))
                .toList());
        if (includeOrphaned) {
            targets.addAll(inventory.byStatus(FunctionInventory.Status.ORPHANED));
        }
        // Prune only removes SQL it can prove it generated: a function without tieto's ownership
        // marker is hand-written (or predates the marker) and is left alone unless --include-unmanaged.
        if (!includeUnmanaged) {
            targets.removeIf(e -> !e.managed());
        }
        return targets;
    }
}
