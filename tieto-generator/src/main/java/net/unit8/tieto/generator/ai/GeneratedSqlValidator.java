package net.unit8.tieto.generator.ai;

import net.unit8.tieto.generator.parser.GeneratorException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates AI-generated SQL before it is deployed or written out.
 *
 * <p>The generator hands whatever the model returns to PostgreSQL. Without a
 * guardrail, a hallucinated apology, a truncated statement, or a destructive
 * statement appended after the function would be executed verbatim. This
 * validator enforces an allowlist: the generated SQL must consist <em>only</em>
 * of {@code CREATE OR REPLACE FUNCTION} statements, and every function defined
 * must be the expected one or its {@code _spec_to_sql} helper.</p>
 *
 * <p>Parsing is dollar-quote aware, so a {@code ;} or a keyword like
 * {@code DROP} inside a function body is part of that body, not a top-level
 * statement.</p>
 */
public final class GeneratedSqlValidator {

    // The function name is either a double-quoted identifier (case-preserved) or
    // a bare identifier (PostgreSQL folds it to lower case). A schema qualifier is
    // intentionally NOT permitted: the generated functions are unqualified, so a
    // schema-qualified name (e.g. pg_catalog.foo) must be rejected, not stripped.
    private static final Pattern CREATE_FUNCTION_HEAD = Pattern.compile(
            "(?is)\\s*CREATE\\s+OR\\s+REPLACE\\s+FUNCTION\\s+(?:\"([^\"]+)\"|([A-Za-z_]\\w*))");

    /**
     * Validates that {@code sql} defines only the expected function (and, when
     * {@code allowSpecHelper} is true, its {@code _spec_to_sql} helper).
     *
     * @param sql the generated SQL
     * @param expectedFunctionName the function name the generator asked for (lower case)
     * @param allowSpecHelper whether a {@code <name>_spec_to_sql} helper is expected
     *        (true only for methods that take a composable Specification)
     * @throws GeneratorException if the SQL contains anything other than the
     *         allowed {@code CREATE OR REPLACE FUNCTION} statements, or does not
     *         define the expected function
     */
    public void validate(String sql, String expectedFunctionName, boolean allowSpecHelper) {
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(expectedFunctionName);
        if (allowSpecHelper) {
            allowed.add(expectedFunctionName + "_spec_to_sql");
        }

        List<String> statements = splitTopLevelStatements(sql);
        if (statements.isEmpty()) {
            throw new GeneratorException(
                    "Generated SQL for " + expectedFunctionName
                            + " contains no statement (expected CREATE OR REPLACE FUNCTION)");
        }

        boolean definedExpected = false;
        for (String statement : statements) {
            Matcher head = CREATE_FUNCTION_HEAD.matcher(statement);
            if (!head.lookingAt()) {
                throw new GeneratorException(
                        "Generated SQL for " + expectedFunctionName
                                + " contains a non-CREATE-FUNCTION statement: "
                                + preview(statement));
            }
            String name = functionName(head);
            if (!allowed.contains(name)) {
                throw new GeneratorException(
                        "Generated SQL defines an unexpected function '" + name
                                + "'; expected one of " + allowed);
            }
            if (name.equals(expectedFunctionName)) {
                definedExpected = true;
            }
            if (allowSpecHelper) {
                String body = functionBody(statement);
                if (body == null) {
                    throw new GeneratorException(name + " has no dollar-quoted body to verify;"
                            + " spec functions must use a dollar-quoted body (AS $$ ... $$ or"
                            + " AS $tag$ ... $tag$) so the parameterized contract can be checked.");
                }
                // Parameterized-spec contract: neither function may extract a spec
                // VALUE into the SQL it builds (only ->>'kind' for dispatch); the
                // main function must bind the spec via EXECUTE ... USING.
                checkNoSpecValueExtraction(body, name);
                if (name.equals(expectedFunctionName)) {
                    checkBindsViaUsing(stripLiteralsAndComments(body), name);
                }
            }
        }

        if (!definedExpected) {
            throw new GeneratorException(
                    "Generated SQL does not define the expected function "
                            + expectedFunctionName);
        }
    }

    /**
     * Resolves the function name from a {@link #CREATE_FUNCTION_HEAD} match,
     * mirroring PostgreSQL identifier folding: a quoted identifier keeps its
     * case; a bare identifier folds to lower case.
     */
    private static String functionName(Matcher head) {
        String quoted = head.group(1);
        if (quoted != null) {
            return quoted;
        }
        return head.group(2).toLowerCase(Locale.ROOT);
    }

    private static final Pattern EXECUTE_KW = Pattern.compile("(?i)\\bEXECUTE\\b");
    private static final Pattern USING_KW = Pattern.compile("(?i)\\bUSING\\b");
    private static final Pattern RETURNS_KW = Pattern.compile("(?i)\\bRETURNS\\b");
    private static final Pattern SPEC_PARAM = Pattern.compile("(?i)\\bspec\\b");

    /**
     * Best-effort backstop for the parameterized-spec contract. The contract — spec
     * values are referenced from the bound spec ({@code $1 #>> path}) inside the
     * EXECUTEd string and never read in code — is what makes injection impossible;
     * the runtime guarantee is proven by the integration test. This check catches the
     * deviations a model is most likely to make: extracting a value <em>in code</em>
     * with {@code ->>} (the only allowed key is {@code 'kind'}, for dispatch) or with
     * {@code #>>}. It runs on the body with strings and comments removed, so an emitted
     * {@code $1 #>> path} or a {@code ->>} inside a comment is not mistaken for code. It
     * is not a complete proof: an exotic extraction (e.g. {@code (node->'x')::text}) can
     * still slip past — the runtime binding, not this scan, is the boundary.
     */
    private static void checkNoSpecValueExtraction(String body, String functionName) {
        int i = 0;
        int n = body.length();
        while (i < n) {
            char c = body.charAt(i);
            // Skip the parts that are not executable code: comments, string literals,
            // and dollar-quoted regions (where an emitted "$1 #>> path" lives).
            if (c == '-' && i + 1 < n && body.charAt(i + 1) == '-') {
                int nl = body.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && body.charAt(i + 1) == '*') {
                int end = body.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '\'') {
                i = endOfSingleQuote(body, i);
                continue;
            }
            int de = dollarQuoteEnd(body, i);
            if (de > i) {
                i = de;
                continue;
            }
            // Code: forbid #>> (path-to-text value extraction) and any non-kind ->>.
            if (c == '#' && i + 2 < n && body.charAt(i + 1) == '>' && body.charAt(i + 2) == '>') {
                throw new GeneratorException(
                        functionName + " extracts a spec value in code with #>>. Reference leaf"
                                + " values from the bound spec ($1 #>> path) inside the EXECUTEd SQL"
                                + " string, not in the function code.");
            }
            if (c == '-' && i + 2 < n && body.charAt(i + 1) == '>' && body.charAt(i + 2) == '>') {
                String key = keyAfter(body, i + 3);
                if (!"kind".equals(key)) {
                    throw new GeneratorException(
                            functionName + " extracts a spec value with ->>"
                                    + (key == null ? "" : " '" + key + "'")
                                    + " and would embed it in SQL. Only ->>'kind' (for dispatch) is"
                                    + " allowed; reference leaf values from the bound spec via a path"
                                    + " ($1 #>> path) so they are passed as bind parameters, not"
                                    + " concatenated.");
                }
                i += 3;
                continue;
            }
            i++;
        }
    }

    private static int endOfSingleQuote(String body, int i) {
        int n = body.length();
        i++;   // past the opening quote
        while (i < n) {
            if (body.charAt(i) == '\'') {
                if (i + 1 < n && body.charAt(i + 1) == '\'') {
                    i += 2;   // escaped quote
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    /**
     * The main spec function builds a dynamic WHERE clause, so it must run it with the
     * spec bound: {@code EXECUTE ... USING spec}. Looks for an EXECUTE whose own statement
     * (up to the next {@code ;}) has a USING clause that binds {@code spec}. Strings and
     * comments are already removed, so the keywords cannot be satisfied by a stray word in
     * a comment or literal, and the USING must belong to the same statement as the EXECUTE.
     */
    private static void checkBindsViaUsing(String code, String functionName) {
        Matcher execute = EXECUTE_KW.matcher(code);
        boolean any = false;
        while (execute.find()) {
            any = true;
            int semicolon = code.indexOf(';', execute.end());
            int stmtEnd = semicolon < 0 ? code.length() : semicolon;
            String statement = code.substring(execute.end(), stmtEnd);
            Matcher using = USING_KW.matcher(statement);
            // EVERY EXECUTE must bind the spec — one unbound EXECUTE could run a
            // concatenated query even if another, harmless EXECUTE does bind spec.
            if (!(using.find() && SPEC_PARAM.matcher(statement).find(using.end()))) {
                throw new GeneratorException(
                        functionName + " has an EXECUTE that does not bind the spec via USING spec;"
                                + " every dynamic statement must run with USING spec so values are"
                                + " bound, not concatenated.");
            }
        }
        if (!any) {
            throw new GeneratorException(
                    functionName + " must run its dynamic WHERE clause with EXECUTE ... USING spec"
                            + " (binding the spec parameter) so spec values are bound, not concatenated.");
        }
    }

    /** Reads the simple quoted/bare key immediately after a {@code ->>} at {@code from}, or null. */
    private static String keyAfter(String body, int from) {
        int n = body.length();
        int k = skipWsFwd(body, from);
        if (k >= n) {
            return null;
        }
        char c = body.charAt(k);
        if (c == '\'' || c == '"') {
            int start = k + 1;
            int end = body.indexOf(c, start);
            return end < 0 ? null : body.substring(start, end);
        }
        int start = k;
        while (k < n && isIdentChar(body.charAt(k))) {
            k++;
        }
        return k == start ? null : body.substring(start, k);
    }

    private static int skipWsFwd(String body, int k) {
        while (k < body.length() && Character.isWhitespace(body.charAt(k))) {
            k++;
        }
        return k;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * The function body: the content of the first dollar-quoted region that follows the
     * {@code RETURNS} clause. Anchoring on {@code RETURNS} skips a dollar-quoted parameter
     * DEFAULT, which appears earlier. Returns null when there is no dollar-quoted body
     * (e.g. a single-quoted {@code AS '...'} body), so the caller can fail closed.
     */
    private static String functionBody(String statement) {
        // Locate RETURNS on a strings/comments-stripped copy (length-preserving, so
        // indices align) so a 'RETURNS' inside a parameter DEFAULT literal cannot
        // anchor the search before the real RETURNS clause.
        Matcher returns = RETURNS_KW.matcher(stripLiteralsAndComments(statement));
        int from = returns.find() ? returns.end() : 0;
        int n = statement.length();
        for (int i = from; i < n; i++) {
            if (statement.charAt(i) != '$') {
                continue;
            }
            if (i + 1 < n && Character.isDigit(statement.charAt(i + 1))) {
                continue;   // $9 is a parameter, not a dollar-quote tag
            }
            int j = i + 1;
            while (j < n && (Character.isLetterOrDigit(statement.charAt(j)) || statement.charAt(j) == '_')) {
                j++;
            }
            if (j < n && statement.charAt(j) == '$') {
                String tag = statement.substring(i, j + 1);
                int close = statement.indexOf(tag, j + 1);
                return close < 0 ? null : statement.substring(j + 1, close);
            }
        }
        return null;
    }

    /** Replaces SQL string-literal contents, dollar-quoted regions, and comments with spaces. */
    private static String stripLiteralsAndComments(String body) {
        int n = body.length();
        StringBuilder out = new StringBuilder(n);
        int i = 0;
        while (i < n) {
            char c = body.charAt(i);
            if (c == '-' && i + 1 < n && body.charAt(i + 1) == '-') {
                while (i < n && body.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && body.charAt(i + 1) == '*') {
                int end = body.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                while (i < stop) {
                    out.append(' ');
                    i++;
                }
                continue;
            }
            if (c == '\'') {
                out.append(' ');
                i++;
                while (i < n) {
                    char ch = body.charAt(i);
                    if (ch == '\'') {
                        if (i + 1 < n && body.charAt(i + 1) == '\'') {
                            out.append("  ");
                            i += 2;
                            continue;
                        }
                        out.append(' ');
                        i++;
                        break;
                    }
                    out.append(' ');
                    i++;
                }
                continue;
            }
            int de = dollarQuoteEnd(body, i);
            if (de > i) {
                for (int k = i; k < de; k++) {
                    out.append(' ');
                }
                i = de;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Splits {@code sql} into top-level statements on {@code ;}, skipping line
     * comments, block comments, single-quoted strings, and dollar-quoted strings
     * so that punctuation inside them does not split a statement. Comments are
     * dropped from the returned statements; string and dollar-quote contents are
     * preserved.
     */
    private static List<String> splitTopLevelStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        int n = sql.length();

        while (i < n) {
            char c = sql.charAt(i);

            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                current.append(' ');   // a comment separates tokens
                i = endOfLine(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                current.append(' ');   // a comment separates tokens
                i = endOfBlockComment(sql, i);
                continue;
            }
            if (c == '\'') {
                i = consumeSingleQuoted(sql, i, current);
                continue;
            }
            int dollarEnd = dollarQuoteEnd(sql, i);
            if (dollarEnd > i) {
                current.append(sql, i, dollarEnd);
                i = dollarEnd;
                continue;
            }
            if (c == ';') {
                addIfNotBlank(statements, current);
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        addIfNotBlank(statements, current);
        return statements;
    }

    private static int endOfLine(String sql, int i) {
        int nl = sql.indexOf('\n', i);
        return nl < 0 ? sql.length() : nl + 1;
    }

    private static int endOfBlockComment(String sql, int i) {
        int end = sql.indexOf("*/", i + 2);
        return end < 0 ? sql.length() : end + 2;
    }

    private static int consumeSingleQuoted(String sql, int i, StringBuilder out) {
        int n = sql.length();
        out.append('\'');
        i++;
        while (i < n) {
            char ch = sql.charAt(i);
            out.append(ch);
            i++;
            if (ch == '\'') {
                if (i < n && sql.charAt(i) == '\'') {
                    out.append('\'');   // escaped quote inside the literal
                    i++;
                } else {
                    return i;   // closed
                }
            }
        }
        throw new GeneratorException(
                "Generated SQL has an unterminated string literal"
                        + " — the model output is likely truncated");
    }

    /**
     * If a dollar-quote opens at {@code i}, returns the index just past its
     * matching close; otherwise returns {@code i} (no dollar-quote here).
     */
    private static int dollarQuoteEnd(String sql, int i) {
        if (sql.charAt(i) != '$') {
            return i;
        }
        int n = sql.length();
        // A dollar-quote tag follows identifier rules: it cannot start with a
        // digit. So $9$ is NOT a dollar-quote (it is the parameter $9 then $),
        // matching how PostgreSQL/pgjdbc tokenize it.
        if (i + 1 < n && Character.isDigit(sql.charAt(i + 1))) {
            return i;
        }
        int j = i + 1;
        while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
            j++;
        }
        if (j >= n || sql.charAt(j) != '$') {
            return i;   // not a dollar-quote opener (e.g. a $1 parameter)
        }
        String tag = sql.substring(i, j + 1);   // includes both '$'
        int close = sql.indexOf(tag, j + 1);
        if (close < 0) {
            throw new GeneratorException(
                    "Generated SQL has an unterminated dollar-quoted string ("
                            + tag + ") — the model output is likely truncated");
        }
        return close + tag.length();
    }

    private static void addIfNotBlank(List<String> statements, StringBuilder current) {
        String trimmed = current.toString().trim();
        if (!trimmed.isEmpty()) {
            statements.add(trimmed);
        }
    }

    private static String preview(String statement) {
        String oneLine = statement.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "..." : oneLine;
    }
}
