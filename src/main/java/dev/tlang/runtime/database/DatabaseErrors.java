package dev.tlang.runtime.database;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;

/** Provider-aware conversion from JDBC failures to stable, credential-safe messages. */
final class DatabaseErrors {
    private DatabaseErrors() {}

    static DatabaseFailure sqlite(SQLException error) {
        String message = error.getMessage();
        return new DatabaseFailure(
            message == null || message.isBlank() ? "Database operation failed." : message,
            error);
    }

    static DatabaseFailure postgres(SQLException error) {
        String state = error.getSQLState();
        String message;
        if (error instanceof SQLTimeoutException || "57014".equals(state)) {
            message = "Database query timed out.";
        } else if (error instanceof SQLTransientConnectionException
                && safeMessage(error).contains("timed out")) {
            message = "Timed out waiting for a database connection.";
        } else if (state != null && state.startsWith("28")) {
            message = "Database authentication failed.";
        } else if (state != null && state.startsWith("08")) {
            message = "Database connection failed.";
        } else if ("23505".equals(state)) {
            message = "Unique constraint violation.";
        } else if ("23503".equals(state)) {
            message = "Foreign key constraint violation.";
        } else if ("23514".equals(state)) {
            message = "Check constraint violation.";
        } else if (state != null && state.startsWith("23")) {
            message = "Database constraint violation.";
        } else if ("42P01".equals(state)) {
            message = "Database table does not exist.";
        } else if ("42601".equals(state)) {
            message = "Invalid SQL statement.";
        } else {
            message = "Database operation failed.";
        }
        return new DatabaseFailure(message, error);
    }

    private static String safeMessage(SQLException error) {
        String message = error.getMessage();
        return message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
    }
}
