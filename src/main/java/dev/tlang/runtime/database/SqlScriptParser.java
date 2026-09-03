package dev.tlang.runtime.database;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Splits SQL scripts without corrupting quoted, commented, or procedural SQL. */
final class SqlScriptParser {
    private static final Set<String> TRANSACTION_CONTROL = Set.of(
        "BEGIN", "START", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE", "PREPARE");
    enum Dialect { SQLITE, POSTGRESQL }

    private SqlScriptParser() {}

    static List<String> split(String sql, Dialect dialect) {
        List<String> statements = new ArrayList<>();
        StringBuilder statement = new StringBuilder();
        StringBuilder word = new StringBuilder();
        State state = State.NORMAL;
        String dollarDelimiter = null;
        int blockCommentDepth = 0;
        boolean executable = false;
        boolean escapeString = false;
        TriggerState trigger = new TriggerState(dialect == Dialect.SQLITE);

        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';

            if (state == State.LINE_COMMENT) {
                statement.append(current);
                if (current == '\n' || current == '\r') state = State.NORMAL;
                continue;
            }
            if (state == State.BLOCK_COMMENT) {
                statement.append(current);
                if (current == '/' && next == '*') {
                    statement.append(next);
                    blockCommentDepth++;
                    index++;
                } else if (current == '*' && next == '/') {
                    statement.append(next);
                    blockCommentDepth--;
                    index++;
                    if (blockCommentDepth == 0) state = State.NORMAL;
                }
                continue;
            }
            if (state == State.SINGLE_QUOTE) {
                statement.append(current);
                if (escapeString && current == '\\' && next != '\0') {
                    statement.append(next);
                    index++;
                } else if (current == '\'' && next == '\'') {
                    statement.append(next);
                    index++;
                } else if (current == '\'') {
                    state = State.NORMAL;
                }
                continue;
            }
            if (state == State.DOUBLE_QUOTE) {
                statement.append(current);
                if (current == '"' && next == '"') {
                    statement.append(next);
                    index++;
                } else if (current == '"') {
                    state = State.NORMAL;
                }
                continue;
            }
            if (state == State.BACKTICK) {
                statement.append(current);
                if (current == '`' && next == '`') {
                    statement.append(next);
                    index++;
                } else if (current == '`') {
                    state = State.NORMAL;
                }
                continue;
            }
            if (state == State.BRACKET) {
                statement.append(current);
                if (current == ']') state = State.NORMAL;
                continue;
            }
            if (state == State.DOLLAR_QUOTE) {
                if (sql.startsWith(dollarDelimiter, index)) {
                    statement.append(dollarDelimiter);
                    index += dollarDelimiter.length() - 1;
                    state = State.NORMAL;
                } else {
                    statement.append(current);
                }
                continue;
            }

            if (isWordCharacter(current)) {
                word.append(current);
                statement.append(current);
                executable = true;
                continue;
            }

            String completedWord = finishWord(word, trigger);
            if (current == '-' && next == '-') {
                statement.append(current).append(next);
                index++;
                state = State.LINE_COMMENT;
                continue;
            }
            if (current == '/' && next == '*') {
                statement.append(current).append(next);
                index++;
                blockCommentDepth = 1;
                state = State.BLOCK_COMMENT;
                continue;
            }
            if (current == '\'') {
                statement.append(current);
                executable = true;
                escapeString = dialect == Dialect.POSTGRESQL
                    && completedWord != null && completedWord.equalsIgnoreCase("E");
                state = State.SINGLE_QUOTE;
                continue;
            }
            if (current == '"') {
                statement.append(current);
                executable = true;
                state = State.DOUBLE_QUOTE;
                continue;
            }
            if (dialect == Dialect.SQLITE && current == '`') {
                statement.append(current);
                executable = true;
                state = State.BACKTICK;
                continue;
            }
            if (dialect == Dialect.SQLITE && current == '[') {
                statement.append(current);
                executable = true;
                state = State.BRACKET;
                continue;
            }
            if (dialect == Dialect.POSTGRESQL && current == '$') {
                String delimiter = dollarDelimiterAt(sql, index);
                if (delimiter != null) {
                    statement.append(delimiter);
                    index += delimiter.length() - 1;
                    dollarDelimiter = delimiter;
                    executable = true;
                    state = State.DOLLAR_QUOTE;
                    continue;
                }
            }
            if (current == ';') {
                statement.append(current);
                if (!trigger.insideBody()) {
                    addStatement(statements, statement, executable);
                    statement = new StringBuilder();
                    executable = false;
                    trigger = new TriggerState(dialect == Dialect.SQLITE);
                }
                continue;
            }
            if (!Character.isWhitespace(current)) executable = true;
            statement.append(current);
        }

        finishWord(word, trigger);
        if (state == State.SINGLE_QUOTE) fail("Unterminated SQL string literal.");
        if (state == State.DOUBLE_QUOTE || state == State.BACKTICK || state == State.BRACKET) {
            fail("Unterminated quoted SQL identifier.");
        }
        if (state == State.BLOCK_COMMENT) fail("Unterminated SQL block comment.");
        if (state == State.DOLLAR_QUOTE) fail("Unterminated PostgreSQL dollar-quoted block.");
        if (trigger.insideBody()) fail("Unterminated SQLite trigger body.");
        addStatement(statements, statement, executable);
        return List.copyOf(statements);
    }

    static boolean isTransactionControl(String statement) {
        String keyword = firstKeyword(statement);
        return keyword != null && TRANSACTION_CONTROL.contains(keyword);
    }

    private static String firstKeyword(String sql) {
        int index = 0;
        int blockDepth = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (blockDepth > 0) {
                if (current == '/' && next == '*') {
                    blockDepth++;
                    index += 2;
                } else if (current == '*' && next == '/') {
                    blockDepth--;
                    index += 2;
                } else {
                    index++;
                }
                continue;
            }
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (current == '-' && next == '-') {
                index += 2;
                while (index < sql.length() && sql.charAt(index) != '\n'
                        && sql.charAt(index) != '\r') index++;
                continue;
            }
            if (current == '/' && next == '*') {
                blockDepth = 1;
                index += 2;
                continue;
            }
            int start = index;
            while (index < sql.length()
                    && (Character.isLetter(sql.charAt(index)) || sql.charAt(index) == '_')) index++;
            return start == index ? null : sql.substring(start, index).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private static String finishWord(StringBuilder word, TriggerState trigger) {
        if (word.isEmpty()) return null;
        String result = word.toString();
        word.setLength(0);
        trigger.word(result);
        return result;
    }

    private static boolean isWordCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value >= 128;
    }

    private static String dollarDelimiterAt(String sql, int start) {
        int index = start + 1;
        if (index < sql.length() && sql.charAt(index) == '$') return "$$";
        if (index >= sql.length() || !(Character.isLetter(sql.charAt(index)) || sql.charAt(index) == '_')) {
            return null;
        }
        index++;
        while (index < sql.length()
                && (Character.isLetterOrDigit(sql.charAt(index)) || sql.charAt(index) == '_')) {
            index++;
        }
        if (index >= sql.length() || sql.charAt(index) != '$') return null;
        return sql.substring(start, index + 1);
    }

    private static void addStatement(
            List<String> statements, StringBuilder statement, boolean executable) {
        String value = statement.toString().trim();
        if (executable && !value.isEmpty() && !value.equals(";")) statements.add(value);
    }

    private static void fail(String message) {
        throw new DatabaseFailure("Invalid migration SQL: " + message);
    }

    private enum State {
        NORMAL, SINGLE_QUOTE, DOUBLE_QUOTE, BACKTICK, BRACKET, LINE_COMMENT, BLOCK_COMMENT,
        DOLLAR_QUOTE
    }

    /** SQLite trigger programs terminate at their outer END, not their inner semicolons. */
    private static final class TriggerState {
        private final boolean enabled;
        private int prefix;
        private boolean trigger;
        private boolean body;
        private int caseDepth;

        private TriggerState(boolean enabled) {
            this.enabled = enabled;
        }

        private void word(String raw) {
            if (!enabled) return;
            String value = raw.toUpperCase(Locale.ROOT);
            if (!trigger) {
                if (prefix == 0 && value.equals("CREATE")) prefix = 1;
                else if (prefix == 1 && (value.equals("TEMP") || value.equals("TEMPORARY"))) {
                    prefix = 2;
                } else if ((prefix == 1 || prefix == 2) && value.equals("TRIGGER")) {
                    trigger = true;
                } else if (prefix > 0) {
                    prefix = -1;
                }
                return;
            }
            if (!body) {
                if (value.equals("BEGIN")) body = true;
                return;
            }
            if (value.equals("CASE")) {
                caseDepth++;
            } else if (value.equals("END")) {
                if (caseDepth > 0) caseDepth--;
                else body = false;
            }
        }

        private boolean insideBody() {
            return body;
        }
    }
}
