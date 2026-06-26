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
            if (allowSpecHelper && name.equals(expectedFunctionName + "_spec_to_sql")) {
                checkSpecHelperForInjection(firstDollarBody(statement), name);
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

    private static final Set<String> SAFE_WRAPPERS = Set.of("quote_literal", "quote_ident", "format");

    /**
     * Rejects the dominant SQL-injection pattern in a generated {@code _spec_to_sql}
     * helper: a spec value extracted with {@code ->>} that is concatenated into the
     * SQL text with {@code ||} without going through {@code quote_literal()} /
     * {@code quote_ident()} / {@code format()}. The helper's return value is executed
     * as dynamic SQL, so an unescaped value there is reachable from a normal
     * repository call.
     *
     * <p>This is a best-effort backstop to the prompt, not a proof: it catches raw
     * {@code || (spec->>'x')} concatenation, the pattern an LLM is most likely to
     * emit. Values routed through {@code format('... %L', ...)} or
     * {@code quote_literal(...)} are accepted; {@code ->>} used only in a comparison
     * (e.g. the {@code kind} dispatch) or fed to recursion via {@code ->} is not
     * flagged.</p>
     */
    private static void checkSpecHelperForInjection(String body, String functionName) {
        if (body == null) {
            return;
        }
        int idx = 0;
        while ((idx = body.indexOf("->>", idx)) >= 0) {
            if (!safelyWrapped(body, idx) && concatenated(body, idx)) {
                throw new GeneratorException(
                        "Possible SQL injection in " + functionName + ": a spec value (->>) is"
                                + " concatenated into SQL without quote_literal()/format(%L)."
                                + " Build leaf conditions with format('... %L', spec->>'field')"
                                + " or quote_literal(spec->>'field').");
            }
            idx += 3;
        }
    }

    /** Whether the {@code ->>} at {@code arrowIdx} sits inside a quote_literal/quote_ident/format call. */
    private static boolean safelyWrapped(String body, int arrowIdx) {
        int depth = 0;
        for (int k = arrowIdx - 1; k >= 0; k--) {
            char c = body.charAt(k);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    String name = identifierEndingAt(body, k - 1);
                    return name != null && SAFE_WRAPPERS.contains(name.toLowerCase(Locale.ROOT));
                }
                depth--;
            }
        }
        return false;
    }

    private static boolean concatenated(String body, int arrowIdx) {
        return concatBefore(body, arrowIdx) || concatAfter(body, arrowIdx);
    }

    private static boolean concatBefore(String body, int arrowIdx) {
        int k = skipWsBack(body, arrowIdx - 1);
        // Skip the left operand of ->> (an identifier path or a parenthesized group).
        if (k >= 0 && body.charAt(k) == ')') {
            k = matchParenBack(body, k) - 1;
        } else {
            while (k >= 0 && (isIdentChar(body.charAt(k)) || body.charAt(k) == '.')) {
                k--;
            }
        }
        // Skip whitespace and any grouping '(' wrapping the operand.
        k = skipWsBack(body, k);
        while (k >= 0 && body.charAt(k) == '(') {
            k = skipWsBack(body, k - 1);
        }
        return k >= 1 && body.charAt(k) == '|' && body.charAt(k - 1) == '|';
    }

    private static boolean concatAfter(String body, int arrowIdx) {
        int n = body.length();
        int k = skipWsFwd(body, arrowIdx + 3);
        // Skip the key: a quoted string or a bare identifier.
        if (k < n && (body.charAt(k) == '\'' || body.charAt(k) == '"')) {
            char q = body.charAt(k);
            k++;
            while (k < n && body.charAt(k) != q) {
                k++;
            }
            k++;   // past the closing quote
        } else {
            while (k < n && isIdentChar(body.charAt(k))) {
                k++;
            }
        }
        // Skip whitespace and any closing ')' of grouping/casts.
        k = skipWsFwd(body, k);
        while (k < n && body.charAt(k) == ')') {
            k = skipWsFwd(body, k + 1);
        }
        return k + 1 < n && body.charAt(k) == '|' && body.charAt(k + 1) == '|';
    }

    private static String identifierEndingAt(String body, int k) {
        k = skipWsBack(body, k);
        int end = k;
        while (k >= 0 && isIdentChar(body.charAt(k))) {
            k--;
        }
        return k == end ? null : body.substring(k + 1, end + 1);
    }

    private static int matchParenBack(String body, int closeIdx) {
        int depth = 0;
        for (int k = closeIdx; k >= 0; k--) {
            char c = body.charAt(k);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth--;
                if (depth == 0) {
                    return k;
                }
            }
        }
        return 0;
    }

    private static int skipWsBack(String body, int k) {
        while (k >= 0 && Character.isWhitespace(body.charAt(k))) {
            k--;
        }
        return k;
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

    /** Returns the content of the first dollar-quoted region in {@code statement}, or null. */
    private static String firstDollarBody(String statement) {
        int n = statement.length();
        for (int i = 0; i < n; i++) {
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
                if (close < 0) {
                    return null;
                }
                return statement.substring(j + 1, close);
            }
        }
        return null;
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
