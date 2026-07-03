package net.unit8.tieto.generator.command;

import net.unit8.tieto.generator.parser.FunctionNaming;
import net.unit8.tieto.generator.parser.MethodSpec;
import net.unit8.tieto.generator.parser.RepositorySpec;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies the PostgreSQL functions deployed under a repository's naming prefix against its
 * current interface:
 * <ul>
 *   <li><b>current</b> — the name matches a declared method (or its {@code _spec_to_sql} helper) at
 *       its {@code @FunctionVersion};</li>
 *   <li><b>superseded</b> — a different version of a still-declared method (its version was bumped,
 *       and this older function was left deployed) — safe to prune;</li>
 *   <li><b>orphaned</b> — no matching method (removed or renamed), or a sibling repository whose
 *       snake-case prefix overlaps this one — review before dropping.</li>
 * </ul>
 *
 * <p>Ownership is by naming convention only ({@code {repo}_{method}_v{N}} plus {@code _spec_to_sql}),
 * so a hand-written function sharing that shape is indistinguishable from a generated one; the
 * {@code list}/{@code prune} commands treat it accordingly (dry-run, explicit confirmation).</p>
 */
final class FunctionInventory {

    enum Status { CURRENT, SUPERSEDED, ORPHANED }

    /**
     * A deployed function. {@code method} is the snake-case method name parsed from the function
     * name and {@code version} its {@code _vN} number (both null when the name does not fit the
     * {@code {repo}_{method}_v{N}} shape); a {@code _spec_to_sql} helper carries its owner's method
     * and version.
     */
    record Entry(String functionName, String identityArgs, Status status, String method, Integer version) {}

    private final String repositoryName;
    private final String schema;
    private final List<Entry> entries;

    private FunctionInventory(String repositoryName, String schema, List<Entry> entries) {
        this.repositoryName = repositoryName;
        this.schema = schema;
        this.entries = entries;
    }

    String repositoryName() {
        return repositoryName;
    }

    List<Entry> entries() {
        return entries;
    }

    List<Entry> byStatus(Status status) {
        return entries.stream().filter(e -> e.status() == status).toList();
    }

    static FunctionInventory of(Connection conn, RepositorySpec repo) {
        String prefix = FunctionNaming.camelToSnake(repo.simpleName()) + "_";

        // The exact names the current interface expects, and the set of method names it declares.
        Set<String> currentNames = new HashSet<>();
        Set<String> declaredMethodSnakes = new HashSet<>();
        for (MethodSpec method : repo.methods()) {
            String name = FunctionNaming.functionName(repo, method);
            currentNames.add(name);
            if (method.hasSpecParameter()) {
                currentNames.add(name + "_spec_to_sql");
            }
            declaredMethodSnakes.add(FunctionNaming.camelToSnake(method.name()));
        }

        // {prefix}{method}_v{N} with an optional _spec_to_sql helper suffix.
        Pattern shape = Pattern.compile("^" + Pattern.quote(prefix) + "(.+)_v(\\d+)(?:_spec_to_sql)?$");

        List<Entry> entries = new ArrayList<>();
        for (DeployedFunctions.DeployedFunction fn : DeployedFunctions.listByPrefix(conn, prefix)) {
            Matcher m = shape.matcher(fn.name());
            String method = m.matches() ? m.group(1) : null;
            Integer version = m.matches() ? Integer.valueOf(m.group(2)) : null;

            Status status;
            if (currentNames.contains(fn.name())) {
                status = Status.CURRENT;
            } else if (method != null && declaredMethodSnakes.contains(method)) {
                // A version of a method the interface still declares, but not its current version.
                status = Status.SUPERSEDED;
            } else {
                status = Status.ORPHANED;
            }
            entries.add(new Entry(fn.name(), fn.identityArgs(), status, method, version));
        }
        return new FunctionInventory(repo.simpleName(), currentSchema(conn), entries);
    }

    private static String currentSchema(Connection conn) {
        try (var st = conn.createStatement();
                var rs = st.executeQuery("SELECT current_schema()")) {
            return rs.next() ? rs.getString(1) : "public";
        } catch (Exception e) {
            return "public";
        }
    }

    String schema() {
        return schema;
    }
}
