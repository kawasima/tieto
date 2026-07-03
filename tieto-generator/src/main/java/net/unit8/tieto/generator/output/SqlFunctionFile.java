package net.unit8.tieto.generator.output;

import net.unit8.tieto.generator.ai.GeneratedFunction;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The on-disk {@code <repository>.sql} file modelled as a header plus a set of tieto-owned
 * function blocks, each delimited by machine-readable comments:
 *
 * <pre>{@code
 * -- tieto:begin order_repository_find_by_id_v1
 * CREATE OR REPLACE FUNCTION order_repository_find_by_id_v1(...) ... $$ ... $$;
 * -- tieto:end order_repository_find_by_id_v1
 * }</pre>
 *
 * <p>The delimiters let a regenerated function replace only its own block, and blocks are always
 * rendered in a stable name-sorted order, so a partial regeneration produces a diff limited to the
 * changed function(s) rather than churning the whole file (which also keeps a Flyway repeatable
 * migration's checksum stable). Parsing keys only on the delimiter comment lines and ends a block
 * on its own {@code -- tieto:end <name>} marker, so an ordinary dollar-quoted body is irrelevant
 * (the one caveat: a body line that is itself exactly this block's end marker would end it early —
 * vanishingly unlikely for generated SQL). CRLF line endings are tolerated. A
 * {@code <name>_spec_to_sql} helper lives inside its owner's block — the block content is the whole
 * {@link GeneratedFunction#sqlBody()} — so grouping is automatic.</p>
 */
final class SqlFunctionFile {

    static final String BEGIN_PREFIX = "-- tieto:begin ";
    static final String END_PREFIX = "-- tieto:end ";

    private static final Pattern BEGIN = Pattern.compile("^-- tieto:begin (\\S+).*$");
    private static final Pattern END = Pattern.compile("^-- tieto:end (\\S+).*$");

    private final String header;                 // text before the first block (no trailing blanks)
    private final Map<String, String> blocks;    // name -> SQL body (no delimiters)
    private final boolean legacy;                // an existing file that had no tieto markers

    private SqlFunctionFile(String header, Map<String, String> blocks, boolean legacy) {
        this.header = header;
        this.blocks = blocks;
        this.legacy = legacy;
    }

    /** A fresh file with the given header and no blocks. */
    static SqlFunctionFile fresh(String header) {
        return new SqlFunctionFile(header.stripTrailing(), new TreeMap<>(), false);
    }

    /**
     * Parses an existing file. {@link #isLegacy()} is true when it contains no {@code tieto:begin}
     * markers (a file written before the delimiters existed, or hand-authored) — the caller must
     * not merge such a file, since its blocks cannot be located.
     */
    static SqlFunctionFile parse(String content) {
        String[] lines = content.split("\r?\n", -1);   // tolerate CRLF line endings
        Map<String, String> blocks = new TreeMap<>();
        StringBuilder header = new StringBuilder();
        boolean sawBlock = false;
        boolean preMarkerJunk = false;   // raw (non-comment) SQL before the first marker
        int i = 0;
        while (i < lines.length) {
            Matcher begin = BEGIN.matcher(lines[i]);
            if (begin.matches()) {
                sawBlock = true;
                String name = begin.group(1);
                StringBuilder body = new StringBuilder();
                i++;
                // Terminate on this block's OWN end marker so an unrelated `-- tieto:end x` line
                // inside a body cannot truncate the block.
                while (i < lines.length && !isEndOf(lines[i], name)) {
                    body.append(lines[i]).append('\n');
                    i++;
                }
                if (i < lines.length) {
                    i++;   // consume the matching -- tieto:end line
                }
                blocks.put(name, body.toString().strip());
                continue;
            }
            if (!sawBlock) {
                // Only comments/blanks belong before the first block. Raw SQL here (e.g. a
                // hand-added function) cannot be merged safely — it would be swallowed into the
                // header and silently duplicated — so mark the file legacy for the append fallback.
                if (!lines[i].isBlank() && !lines[i].stripLeading().startsWith("--")) {
                    preMarkerJunk = true;
                }
                header.append(lines[i]).append('\n');
            }
            i++;
        }
        return new SqlFunctionFile(header.toString().stripTrailing(), blocks, !sawBlock || preMarkerJunk);
    }

    private static boolean isEndOf(String line, String name) {
        Matcher end = END.matcher(line);
        return end.matches() && end.group(1).equals(name);
    }

    boolean isLegacy() {
        return legacy;
    }

    /** Adds or replaces the block for each generated function, keyed by its function name. */
    void putAll(List<GeneratedFunction> functions) {
        for (GeneratedFunction func : functions) {
            blocks.put(func.functionName(), terminate(func.sqlBody()));
        }
    }

    /** Renders the header followed by every block in name-sorted order, each wrapped in delimiters. */
    String render() {
        StringBuilder sb = new StringBuilder();
        if (!header.isEmpty()) {
            sb.append(header).append("\n\n");
        }
        boolean first = true;
        for (Map.Entry<String, String> block : blocks.entrySet()) {   // TreeMap => sorted by name
            if (!first) {
                sb.append('\n');
            }
            sb.append(BEGIN_PREFIX).append(block.getKey()).append('\n')
                    .append(block.getValue()).append('\n')
                    .append(END_PREFIX).append(block.getKey()).append('\n');
            first = false;
        }
        return sb.toString();
    }

    /** Trims surrounding whitespace and ensures the SQL ends with a single {@code ;}. */
    private static String terminate(String sqlBody) {
        String trimmed = sqlBody.strip();
        return trimmed.endsWith(";") ? trimmed : trimmed + ";";
    }
}
