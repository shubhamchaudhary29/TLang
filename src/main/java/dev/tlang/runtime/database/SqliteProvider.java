package dev.tlang.runtime.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Existing path-based SQLite behavior behind the provider boundary. */
public final class SqliteProvider implements DatabaseProvider {
    @Override
    public boolean accepts(String target) {
        return !target.regionMatches(true, 0, "postgresql:", 0, "postgresql:".length())
            && !target.regionMatches(true, 0, "postgres:", 0, "postgres:".length());
    }

    @Override
    public DatabaseConnection open(DatabaseOptions options) {
        if (options.target().contains("://") || options.target().startsWith("jdbc:")) {
            throw new DatabaseFailure("Unsupported database target. Use a SQLite path or postgresql:// URL.");
        }
        if (options.username() != null || options.password() != null) {
            throw new DatabaseFailure("SQLite connections do not accept username or password options.");
        }
        try {
            Class.forName("org.sqlite.JDBC");
            return new SqliteConnection(
                DriverManager.getConnection("jdbc:sqlite:" + options.target()),
                options.queryTimeoutSeconds());
        } catch (ClassNotFoundException error) {
            throw new DatabaseFailure("SQLite JDBC driver is unavailable.", error);
        } catch (SQLException error) {
            throw DatabaseErrors.sqlite(error);
        }
    }

    private static final class SqliteConnection implements DatabaseConnection {
        private final Object lock = new Object();
        private final int timeoutSeconds;
        private Connection connection;
        private SqliteTransaction activeTransaction;

        private SqliteConnection(Connection connection, int timeoutSeconds) {
            this.connection = connection;
            this.timeoutSeconds = timeoutSeconds;
        }

        @Override
        public List<Object> query(String sql, List<?> parameters) {
            synchronized (lock) {
                return withOpenConnection(false, connection -> JdbcQueries.query(
                    connection, sql, parameters, timeoutSeconds, SqlParameters.Dialect.SQLITE,
                    "SQLite", DatabaseErrors::sqlite));
            }
        }

        @Override
        public int execute(String sql, List<?> parameters) {
            synchronized (lock) {
                return withOpenConnection(false, connection -> JdbcQueries.execute(
                    connection, sql, parameters, timeoutSeconds, SqlParameters.Dialect.SQLITE,
                    DatabaseErrors::sqlite));
            }
        }

        @Override
        public int lastInsertId() {
            synchronized (lock) {
                return withOpenConnection(false, SqliteProvider::readLastInsertId);
            }
        }

        @Override
        public DatabaseTransaction begin() {
            synchronized (lock) {
                Connection current = requireOpen();
                if (activeTransaction != null) {
                    throw new DatabaseFailure("Nested transactions are not supported.");
                }
                try {
                    current.setAutoCommit(false);
                    activeTransaction = new SqliteTransaction(this);
                    return activeTransaction;
                } catch (SQLException error) {
                    throw DatabaseErrors.sqlite(error);
                }
            }
        }

        @Override
        public String providerName() {
            return "sqlite";
        }

        @Override
        public boolean isClosed() {
            synchronized (lock) {
                if (connection == null) return true;
                try {
                    return connection.isClosed();
                } catch (SQLException error) {
                    throw DatabaseErrors.sqlite(error);
                }
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (connection == null) return;
                if (activeTransaction != null) activeTransaction.abortLocked();
                try {
                    connection.close();
                } catch (SQLException error) {
                    throw DatabaseErrors.sqlite(error);
                } finally {
                    connection = null;
                }
            }
        }

        private <T> T withOpenConnection(boolean transactionOperation, SqlWork<T> work) {
            Connection current = requireOpen();
            if (!transactionOperation && activeTransaction != null) {
                throw new DatabaseFailure(
                    "A transaction is active; use its transaction handle until commit or rollback.");
            }
            return work.apply(current);
        }

        private Connection requireOpen() {
            if (connection == null) throw new DatabaseFailure("Connection is closed.");
            try {
                if (connection.isClosed()) throw new DatabaseFailure("Connection is closed.");
                return connection;
            } catch (SQLException error) {
                throw DatabaseErrors.sqlite(error);
            }
        }

        private void clear(SqliteTransaction transaction) {
            if (activeTransaction == transaction) activeTransaction = null;
        }
    }

    private static final class SqliteTransaction implements DatabaseTransaction {
        private final SqliteConnection owner;
        private boolean closed;

        private SqliteTransaction(SqliteConnection owner) {
            this.owner = owner;
        }

        @Override
        public List<Object> query(String sql, List<?> parameters) {
            synchronized (owner.lock) {
                ensureActive();
                try {
                    return owner.withOpenConnection(true, connection -> JdbcQueries.query(
                        connection, sql, parameters, owner.timeoutSeconds,
                        SqlParameters.Dialect.SQLITE, "SQLite", DatabaseErrors::sqlite));
                } catch (DatabaseFailure failure) {
                    abortLocked();
                    throw failure;
                }
            }
        }

        @Override
        public int execute(String sql, List<?> parameters) {
            synchronized (owner.lock) {
                ensureActive();
                try {
                    return owner.withOpenConnection(true, connection -> JdbcQueries.execute(
                        connection, sql, parameters, owner.timeoutSeconds,
                        SqlParameters.Dialect.SQLITE, DatabaseErrors::sqlite));
                } catch (DatabaseFailure failure) {
                    abortLocked();
                    throw failure;
                }
            }
        }

        @Override
        public int lastInsertId() {
            synchronized (owner.lock) {
                ensureActive();
                try {
                    return owner.withOpenConnection(true, SqliteProvider::readLastInsertId);
                } catch (DatabaseFailure failure) {
                    abortLocked();
                    throw failure;
                }
            }
        }

        @Override
        public void commit() {
            finish(true);
        }

        @Override
        public void rollback() {
            finish(false);
        }

        @Override
        public void abort() {
            synchronized (owner.lock) {
                abortLocked();
            }
        }

        private void finish(boolean commit) {
            synchronized (owner.lock) {
                ensureActive();
                Connection connection = owner.requireOpen();
                try {
                    if (commit) connection.commit(); else connection.rollback();
                } catch (SQLException error) {
                    tryRollback(connection);
                    throw DatabaseErrors.sqlite(error);
                } finally {
                    restoreAndClose(connection);
                }
            }
        }

        private void abortLocked() {
            if (closed) return;
            Connection connection = owner.connection;
            if (connection != null) tryRollback(connection);
            restoreAndClose(connection);
        }

        private void restoreAndClose(Connection connection) {
            try {
                if (connection != null && !connection.isClosed()) connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // The original transaction failure remains the useful diagnostic.
            } finally {
                closed = true;
                owner.clear(this);
            }
        }

        private static void tryRollback(Connection connection) {
            try {
                if (!connection.isClosed()) connection.rollback();
            } catch (SQLException ignored) {
                // Rollback is best-effort while preserving the triggering failure.
            }
        }

        private void ensureActive() {
            if (closed || owner.activeTransaction != this) {
                throw new DatabaseFailure("Transaction is closed.");
            }
        }

        @Override
        public boolean isClosed() {
            synchronized (owner.lock) {
                return closed;
            }
        }
    }

    private static int readLastInsertId(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT last_insert_rowid()");
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) return resultSet.getInt(1);
            throw new DatabaseFailure("Failed to retrieve last insert ID.");
        } catch (SQLException error) {
            throw DatabaseErrors.sqlite(error);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T apply(Connection connection);
    }
}
