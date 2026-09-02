package dev.tlang.runtime.database;

import dev.tlang.types.Type;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/** Placeholder validation and JDBC binding; parameter values are never interpolated into SQL. */
final class SqlParameters {
    enum Dialect { SQLITE, POSTGRESQL }

    private SqlParameters() {}

    static void bind(
            PreparedStatement statement,
            String sql,
            List<?> parameters,
            Dialect dialect) throws SQLException {
        int expected = countPlaceholders(sql);
        if (expected != parameters.size()) {
            throw new DatabaseFailure(
                "Expected " + expected + " parameters, but got " + parameters.size() + ".");
        }
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            int jdbcIndex = index + 1;
            if (value == null) {
                statement.setNull(jdbcIndex, Types.NULL);
            } else if (value instanceof Integer integer) {
                statement.setInt(jdbcIndex, integer);
            } else if (value instanceof String string) {
                statement.setString(jdbcIndex, string);
            } else if (value instanceof Boolean bool) {
                if (dialect == Dialect.SQLITE) {
                    statement.setInt(jdbcIndex, bool ? 1 : 0);
                } else {
                    statement.setBoolean(jdbcIndex, bool);
                }
            } else {
                throw new DatabaseFailure(
                    "Unsupported database parameter type: " + Type.of(value).displayName() + ".");
            }
        }
    }

    static Object toTlang(Object value, String providerName) {
        if (value == null || value instanceof Integer || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Long
                || value instanceof BigInteger) {
            return checkedInteger(new BigDecimal(value.toString()), providerName);
        }
        if (value instanceof BigDecimal decimal) {
            return checkedInteger(decimal, providerName);
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number) || number != Math.rint(number)
                    || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw new DatabaseFailure(
                    "Unsupported fractional or out-of-range database number from " + providerName + ".");
            }
            return (int) number;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof java.time.LocalDate
                || value instanceof java.time.LocalDateTime
                || value instanceof java.time.OffsetDateTime
                || value instanceof java.time.LocalTime
                || value instanceof java.time.OffsetTime
                || value instanceof java.time.Instant
                || value instanceof java.util.UUID) {
            return value.toString();
        }
        if (value instanceof byte[]) {
            throw new DatabaseFailure("Binary database values are not supported.");
        }
        throw new DatabaseFailure(
            "Unsupported database value type from " + providerName + ".");
    }

    private static int checkedInteger(BigDecimal decimal, String providerName) {
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException error) {
            throw new DatabaseFailure(
                "Unsupported fractional or out-of-range database number from " + providerName + ".",
                error);
        }
    }

    /** Counts JDBC placeholders while ignoring quoted text, identifiers, comments, and dollar strings. */
    static int countPlaceholders(String sql) {
        int count = 0;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipQuoted(sql, index + 1, '\'');
            } else if (current == '"') {
                index = skipQuoted(sql, index + 1, '"');
            } else if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                index = skipLineComment(sql, index + 2);
            } else if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                index = skipBlockComment(sql, index + 2);
            } else if (current == '$') {
                int skipped = skipDollarQuoted(sql, index);
                index = skipped == index ? index + 1 : skipped;
            } else {
                if (current == '?' && index + 1 < sql.length() && sql.charAt(index + 1) == '?') {
                    // PgJDBC's documented escape for a literal question-mark operator.
                    index += 2;
                } else {
                    if (current == '?') count++;
                    index++;
                }
            }
        }
        return count;
    }

    private static int skipQuoted(String sql, int index, char quote) {
        while (index < sql.length()) {
            if (sql.charAt(index) == quote) {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                    index += 2;
                } else {
                    return index + 1;
                }
            } else {
                index++;
            }
        }
        return index;
    }

    private static int skipLineComment(String sql, int index) {
        while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') index++;
        return index;
    }

    private static int skipBlockComment(String sql, int index) {
        while (index + 1 < sql.length()) {
            if (sql.charAt(index) == '*' && sql.charAt(index + 1) == '/') return index + 2;
            index++;
        }
        return sql.length();
    }

    private static int skipDollarQuoted(String sql, int start) {
        int end = sql.indexOf('$', start + 1);
        if (end < 0) return start;
        String tag = sql.substring(start, end + 1);
        if (!tag.matches("\\$[A-Za-z_][A-Za-z0-9_]*\\$|\\$\\$")) return start;
        int close = sql.indexOf(tag, end + 1);
        return close < 0 ? sql.length() : close + tag.length();
    }
}
