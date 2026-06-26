package net.unit8.tieto.generator.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared post-processing of an AI provider's raw SQL output: stripping a
 * surrounding markdown code fence and reading the generated function's name.
 * Used by every provider (the HTTP providers and {@link CliAiProvider}).
 */
final class ResponseSql {

    private static final Pattern FUNCTION_NAME_PATTERN =
            Pattern.compile("CREATE\\s+OR\\s+REPLACE\\s+FUNCTION\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

    private ResponseSql() {
    }

    /** Removes a leading/trailing ```` ``` ```` fence (with optional language tag) if present. */
    static String stripMarkdownFences(String text) {
        if (text.startsWith("```")) {
            return text.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
        }
        return text;
    }

    /** The name of the {@code CREATE OR REPLACE FUNCTION} in the SQL, or {@code "unknown_function"}. */
    static String extractFunctionName(String sql) {
        Matcher matcher = FUNCTION_NAME_PATTERN.matcher(sql);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "unknown_function";
    }
}
