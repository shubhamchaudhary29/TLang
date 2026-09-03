package dev.tlang.runtime.database;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** JDBC migration storage, transactions, and cross-process database locks. */
final class JdbcMigrationBackend {
    private static final String CREATE_HISTORY = """
        CREATE TABLE IF NOT EXISTS _tlang_migrations (
            version INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            checksum TEXT NOT NULL,
            applied_at TEXT NOT NULL
        )
        """;
    private static final String READ_HISTORY = """
        SELECT version, name, checksum, applied_at
        FROM _tlang_migrations
        ORDER BY version
        """;
    private static final String INSERT_HISTORY = """
        INSERT INTO _tlang_migrations(version, name, checksum, applied_at)
        VALUES (?, ?, ?, ?)
        """;

    private JdbcMigrationBackend() {}

    static <T> T postgres(
            HikariDataSource dataSource,
            int timeoutSeconds,
            MigrationBackend.MigrationWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean locked = acquirePostgresLock(connection, timeoutSeconds);
            if (!locked) {
                throw new DatabaseFailure(
                    "Migration lock could not be acquired within the configured timeout.");
            }
            T result = null;
            RuntimeException operationFailure = null;
            try {
                ensureHistory(connection, timeoutSeconds, DatabaseErrors::postgres);
                result = work.run(new Store(
                    connection, timeoutSeconds, SqlScriptParser.Dialect.POSTGRESQL,
                    DatabaseErrors::postgres));
            } catch (RuntimeException failure) {
                operationFailure = failure;
            }
            boolean released = releasePostgresLock(connection, timeoutSeconds);
            if (!released) dataSource.evictConnection(connection);
            if (operationFailure != null) throw operationFailure;
            if (!released) throw new DatabaseFailure("Migration lock could not be released safely.");
            return result;
        } catch (DatabaseFailure failure) {
            throw failure;
        } catch (SQLException failure) {
            throw new DatabaseFailure("Migration lock could not be acquired.",
                DatabaseErrors.postgres(failure));
        }
    }

    static <T> T sqlite(
            Connection connection,
            int timeoutSeconds,
            MigrationBackend.MigrationWork<T> work) {
        int previousBusyTimeout = readBusyTimeout(connection);
        boolean transaction = false;
        try {
            setBusyTimeout(connection, Math.multiplyExact(timeoutSeconds, 1_000));
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds);
                statement.execute("BEGIN IMMEDIATE");
                transaction = true;
            }
            ensureHistoryInCurrentTransaction(connection, timeoutSeconds, DatabaseErrors::sqlite);
            T result = work.run(new Store(
                connection, timeoutSeconds, SqlScriptParser.Dialect.SQLITE, DatabaseErrors::sqlite));
            executeControl(connection, "COMMIT", timeoutSeconds);
            transaction = false;
            return result;
        } catch (DatabaseFailure failure) {
            if (transaction) rollbackQuietly(connection, timeoutSeconds);
            throw failure;
        } catch (SQLException failure) {
            if (transaction) rollbackQuietly(connection, timeoutSeconds);
            if (failure.getErrorCode() == 5 || failure.getMessage() != null
                    && failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("locked")) {
                throw new DatabaseFailure(
                    "Migration lock could not be acquired within the configured timeout.");
            }
            throw DatabaseErrors.sqlite(failure);
        } catch (ArithmeticException failure) {
            if (transaction) rollbackQuietly(connection, timeoutSeconds);
            throw new DatabaseFailure("Migration lock timeout is invalid.");
        } finally {
            setBusyTimeoutQuietly(connection, previousBusyTimeout);
        }
    }

    private static boolean acquirePostgresLock(Connection connection, int timeoutSeconds)
            throws SQLException {
        long deadline = System.nanoTime()
            + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
        String sql = "SELECT pg_try_advisory_lock(" +
            "hashtext(current_database()), hashtext('_tlang_migrations'))";
        do {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(timeoutSeconds);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next() && result.getBoolean(1)) return true;
                }
            }
            if (System.nanoTime() >= deadline) return false;
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new DatabaseFailure("Migration lock wait was interrupted.");
            }
        } while (true);
    }

    private static boolean releasePostgresLock(Connection connection, int timeoutSeconds) {
        String sql = "SELECT pg_advisory_unlock(" +
            "hashtext(current_database()), hashtext('_tlang_migrations'))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        } catch (SQLException ignored) {
            // The caller evicts this physical session rather than returning a possibly locked one.
            return false;
        }
    }

    private static void ensureHistory(
            Connection connection,
            int timeoutSeconds,
            Function<SQLException, DatabaseFailure> errors) {
        try {
            connection.setAutoCommit(false);
            ensureHistoryInCurrentTransaction(connection, timeoutSeconds, errors);
            connection.commit();
        } catch (DatabaseFailure failure) {
            rollbackJdbcQuietly(connection);
            throw failure;
        } catch (SQLException failure) {
            rollbackJdbcQuietly(connection);
            throw new DatabaseFailure("Migration history could not be initialized.", errors.apply(failure));
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // The connection will be discarded if it cannot be restored.
            }
        }
    }

    private static void ensureHistoryInCurrentTransaction(
            Connection connection,
            int timeoutSeconds,
            Function<SQLException, DatabaseFailure> errors) {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.execute(CREATE_HISTORY);
        } catch (SQLException failure) {
            throw new DatabaseFailure("Migration history could not be initialized.", errors.apply(failure));
        }
    }

    private static int readBusyTimeout(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA busy_timeout")) {
            return result.next() ? result.getInt(1) : 0;
        } catch (SQLException failure) {
            throw DatabaseErrors.sqlite(failure);
        }
    }

    private static void setBusyTimeout(Connection connection, int milliseconds) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = " + milliseconds);
        }
    }

    private static void setBusyTimeoutQuietly(Connection connection, int milliseconds) {
        try {
            setBusyTimeout(connection, milliseconds);
        } catch (SQLException ignored) {
            // Preserve the migration result; the setting is connection-local and non-secret.
        }
    }

    private static void executeControl(Connection connection, String sql, int timeoutSeconds)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.execute(sql);
        }
    }

    private static void rollbackQuietly(Connection connection, int timeoutSeconds) {
        try {
            executeControl(connection, "ROLLBACK", timeoutSeconds);
        } catch (SQLException ignored) {
            // Preserve the triggering migration failure.
        }
    }

    private static void rollbackJdbcQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the triggering migration failure.
        }
    }

    private static final class Store implements MigrationBackend.MigrationStore {
        private final Connection connection;
        private final int timeoutSeconds;
        private final SqlScriptParser.Dialect dialect;
        private final Function<SQLException, DatabaseFailure> errors;

        private Store(
                Connection connection,
                int timeoutSeconds,
                SqlScriptParser.Dialect dialect,
                Function<SQLException, DatabaseFailure> errors) {
            this.connection = connection;
            this.timeoutSeconds = timeoutSeconds;
            this.dialect = dialect;
            this.errors = errors;
        }

        @Override
        public List<MigrationHistoryEntry> history() {
            List<MigrationHistoryEntry> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(READ_HISTORY)) {
                statement.setQueryTimeout(timeoutSeconds);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        int version = rows.getInt(1);
                        if (rows.wasNull()) version = 0;
                        result.add(new MigrationHistoryEntry(
                            version, rows.getString(2), rows.getString(3), rows.getString(4)));
                    }
                }
                return List.copyOf(result);
            } catch (SQLException failure) {
                throw new DatabaseFailure("Migration history is invalid.", errors.apply(failure));
            }
        }

        @Override
        public void apply(MigrationFile migration, String appliedAt) {
            if (dialect == SqlScriptParser.Dialect.POSTGRESQL) {
                applyPostgres(migration, appliedAt);
            } else {
                applyInCurrentTransaction(migration, appliedAt);
            }
        }

        private void applyPostgres(MigrationFile migration, String appliedAt) {
            try {
                connection.setAutoCommit(false);
                executeMigration(migration, appliedAt);
                connection.commit();
            } catch (SQLException failure) {
                rollbackJdbcQuietly(connection);
                throw failed(migration, failure);
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // Closing the connection prevents a broken session returning to the pool.
                }
            }
        }

        private void applyInCurrentTransaction(MigrationFile migration, String appliedAt) {
            try {
                executeMigration(migration, appliedAt);
            } catch (SQLException failure) {
                throw failed(migration, failure);
            }
        }

        private void executeMigration(MigrationFile migration, String appliedAt) throws SQLException {
            for (String sql : migration.statements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(timeoutSeconds);
                    statement.execute(sql);
                    while (statement.getMoreResults() || statement.getUpdateCount() != -1) {
                        // Consume every result from drivers that expose multi-result statements.
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(INSERT_HISTORY)) {
                insert.setQueryTimeout(timeoutSeconds);
                insert.setInt(1, migration.version());
                insert.setString(2, migration.name());
                insert.setString(3, migration.checksum());
                insert.setString(4, appliedAt);
                if (insert.executeUpdate() != 1) {
                    throw new SQLException("Migration history insert did not affect one row.");
                }
            }
        }

        private DatabaseFailure failed(MigrationFile migration, SQLException failure) {
            return new DatabaseFailure(
                "Migration " + migration.filename() + " failed; transaction rolled back.",
                errors.apply(failure));
        }
    }
}
