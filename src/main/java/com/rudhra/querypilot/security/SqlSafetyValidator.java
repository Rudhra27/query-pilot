package com.rudhra.querypilot.security;

import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SqlSafetyValidator {

    private static final Set<String> ALLOWED_STARTS = Set.of(
            "SELECT",
            "WITH"
    );

    private static final Set<String> BLOCKED_KEYWORDS = Set.of(
            "INSERT",
            "UPDATE",
            "DELETE",
            "DROP",
            "ALTER",
            "TRUNCATE",
            "CREATE",
            "GRANT",
            "REVOKE",
            "MERGE",
            "CALL",
            "DO",
            "COPY",
            "VACUUM",
            "ANALYZE",
            "REFRESH"
    );

    private static final Pattern SQL_KEYWORD_PATTERN = Pattern.compile("\\b([A-Za-z]+)\\b");

    public void validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new UnsafeSqlException("SQL query cannot be empty");
        }
        String normalizedSql = sql.trim();

        // We only accept a single statement.
        if (containsMultipleStatements(normalizedSql)) {
            throw new UnsafeSqlException("Only a single read-only SQL statement is allowed");
        }

        String withoutComments = removeComments(normalizedSql).trim();
        String firstKeyword = extractFirstKeyword(withoutComments);

        if (!ALLOWED_STARTS.contains(firstKeyword)) {
            throw new UnsafeSqlException("Only SELECT or WITH queries are allowed");
        }

        var matcher = SQL_KEYWORD_PATTERN.matcher(withoutComments);

        while (matcher.find()) {
            String keyword = matcher.group(1).toUpperCase();
            if (BLOCKED_KEYWORDS.contains(keyword)) {
                throw new UnsafeSqlException("SQL statement contains a prohibited operation: " + keyword);
            }
        }
    }

    private String extractFirstKeyword(String sql) {
        var matcher = SQL_KEYWORD_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).toUpperCase();
    }

    private boolean containsMultipleStatements(String sql) {
        String withoutStrings = sql.replaceAll("'([^']|'')*'", "").trim();
        if (withoutStrings.endsWith(";")) {
            withoutStrings = withoutStrings.substring(0, withoutStrings.length() - 1);
        }
        return withoutStrings.contains(";");
    }

    private String removeComments(String sql) {
        // Remove -- comments
        String withoutLineComments = sql.replaceAll("(?m)--.*$", "");

        // Remove /* ... */ comments
        return withoutLineComments.replaceAll("/\\*.*?\\*/", " ");
    }
}