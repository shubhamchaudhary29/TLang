package dev.tlang.runtime.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** PostgreSQL provider with one bounded pool per public database handle. */
public final class PostgresProvider implements DatabaseProvider {
    private static final AtomicLong POOL_SEQUENCE = new AtomicLong();

    @Override
    public boolean accepts(String target) {
        return target.regionMatches(true, 0, "postgresql:", 0, "postgresql:".length())
            || target.regionMatches(true, 0, "postgres:", 0, "postgres:".length());
    }

    @Override
    public DatabaseConnection open(DatabaseOptions options) {
        ParsedTarget target = parse(options.target());
        String username = options.username() != null ? options.username() : target.username;
        String password = options.password() != null ? options.password() : target.password;

        HikariConfig config = new HikariConfig();
        config.setPoolName("tlang-postgres-" + POOL_SEQUENCE.incrementAndGet());
        config.setJdbcUrl(target.jdbcUrl);
        if (username != null) config.setUsername(username);
        if (password != null) config.setPassword(password);
        config.setMaximumPoolSize(options.poolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(options.connectionTimeoutMs());
        config.setValidationTimeout(Math.min(options.connectionTimeoutMs(), 5_000));
        config.setInitializationFailTimeout(options.connectionTimeoutMs());
        config.setAutoCommit(true);
        config.setRegisterMbeans(false);

        HikariDataSource dataSource = null;
        try {
            dataSource = new HikariDataSource(config);
            try (Connection connection = dataSource.getConnection()) {
                if (!connection.isValid(Math.max(1, Math.min(5, options.queryTimeoutSeconds())))) {
                    throw new DatabaseFailure("Database connection validation failed.");
                }
            }
        } catch (DatabaseFailure failure) {
            closeQuietly(dataSource);
            throw failure;
        } catch (SQLException error) {
            closeQuietly(dataSource);
            throw DatabaseErrors.postgres(error);
        } catch (RuntimeException error) {
            closeQuietly(dataSource);
            SQLException sqlError = findSqlException(error);
            if (sqlError != null) throw DatabaseErrors.postgres(sqlError);
            throw new DatabaseFailure("Database connection failed.", error);
        }
        return new PooledPostgresConnection(dataSource, options.queryTimeoutSeconds());
    }

    private static void closeQuietly(HikariDataSource dataSource) {
        if (dataSource != null) dataSource.close();
    }

    private static ParsedTarget parse(String value) {
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException error) {
            throw new DatabaseFailure("Malformed PostgreSQL URL.");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("postgresql") || scheme.equalsIgnoreCase("postgres"))
                || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new DatabaseFailure(
                "Malformed PostgreSQL URL. Expected postgresql://host[:port]/database without URL options.");
        }
        int port = uri.getPort();
        if (port < -1 || port == 0 || port > 65_535) {
            throw new DatabaseFailure("Malformed PostgreSQL URL port.");
        }
        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.length() <= 1 || rawPath.substring(1).contains("/")) {
            throw new DatabaseFailure("PostgreSQL URL must name exactly one database.");
        }
        String database = decode(rawPath.substring(1));
        if (database.isBlank() || database.contains("/") || containsControl(database)) {
            throw new DatabaseFailure("PostgreSQL database name is invalid.");
        }

        String username = null;
        String password = null;
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int separator = userInfo.indexOf(':');
            username = decode(separator < 0 ? userInfo : userInfo.substring(0, separator));
            password = separator < 0 ? null : decode(userInfo.substring(separator + 1));
            if (username.isBlank() || containsControl(username)
                    || (password != null && containsControl(password))) {
                throw new DatabaseFailure("PostgreSQL credentials in URL are invalid.");
            }
        }

        String host = uri.getHost();
        String jdbcHost = host.contains(":") ? "[" + host + "]" : host;
        String jdbcUrl = "jdbc:postgresql://" + jdbcHost + (port < 0 ? "" : ":" + port)
            + "/" + encodePathSegment(database);
        return new ParsedTarget(jdbcUrl, username, password);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new DatabaseFailure("Malformed percent-encoding in PostgreSQL URL.");
        }
    }

    private static String encodePathSegment(String value) {
        StringBuilder result = new StringBuilder();
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = current & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z') || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9') || unsigned == '-' || unsigned == '_'
                    || unsigned == '.' || unsigned == '~') {
                result.append((char) unsigned);
            } else {
                result.append('%').append(String.format("%02X", unsigned));
            }
        }
        return result.toString();
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(character -> Character.isISOControl(character));
    }

    private static SQLException findSqlException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException) return sqlException;
            current = current.getCause();
        }
        return null;
    }

    private record ParsedTarget(String jdbcUrl, String username, String password) {}

    private static final class PooledPostgresConnection implements DatabaseConnection {
        private final HikariDataSource dataSource;
        private final int timeoutSeconds;
        private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
        private final Set<PostgresTransaction> transactions = new HashSet<>();
        private boolean closed;

        private PooledPostgresConnection(HikariDataSource dataSource, int timeoutSeconds) {
            this.dataSource = dataSource;
            this.timeoutSeconds = timeoutSeconds;
        }

        @Override
        public List<Object> query(String sql, List<?> parameters) {
            lifecycle.readLock().lock();
            try {
                ensureOpen();
                try (Connection connection = dataSource.getConnection()) {
                    return JdbcQueries.query(connection, sql, parameters, timeoutSeconds,
                        SqlParameters.Dialect.POSTGRESQL, "PostgreSQL", DatabaseErrors::postgres);
                } catch (SQLException error) {
                    throw DatabaseErrors.postgres(error);
                }
            } finally {
                lifecycle.readLock().unlock();
            }
        }

        @Override
        public int execute(String sql, List<?> parameters) {
            lifecycle.readLock().lock();
            try {
                ensureOpen();
                try (Connection connection = dataSource.getConnection()) {
                    return JdbcQueries.execute(connection, sql, parameters, timeoutSeconds,
                        SqlParameters.Dialect.POSTGRESQL, DatabaseErrors::postgres);
                } catch (SQLException error) {
                    throw DatabaseErrors.postgres(error);
                }
            } finally {
                lifecycle.readLock().unlock();
            }
        }

        @Override
        public int lastInsertId() {
            throw new DatabaseFailure(
                "lastInsertId() is SQLite-only; use an INSERT ... RETURNING query with PostgreSQL.");
        }

        @Override
        public DatabaseTransaction begin() {
            lifecycle.readLock().lock();
            try {
                ensureOpen();
                try {
                    Connection connection = dataSource.getConnection();
                    try {
                        connection.setAutoCommit(false);
                        PostgresTransaction transaction = new PostgresTransaction(
                            this, connection, timeoutSeconds);
                        synchronized (transactions) {
                            if (closed) {
                                transaction.abort();
                                throw new DatabaseFailure("Connection is closed.");
                            }
                            transactions.add(transaction);
                        }
                        return transaction;
                    } catch (RuntimeException | SQLException failure) {
                        try { connection.close(); } catch (SQLException ignored) {}
                        if (failure instanceof SQLException sqlFailure) {
                            throw DatabaseErrors.postgres(sqlFailure);
                        }
                        throw failure;
                    }
                } catch (SQLException error) {
                    throw DatabaseErrors.postgres(error);
                }
            } finally {
                lifecycle.readLock().unlock();
            }
        }

        @Override
        public String providerName() {
            return "postgresql";
        }

        @Override
        public boolean isClosed() {
            lifecycle.readLock().lock();
            try {
                return closed;
            } finally {
                lifecycle.readLock().unlock();
            }
        }

        @Override
        public void close() {
            lifecycle.writeLock().lock();
            try {
                if (closed) return;
                closed = true;
                PostgresTransaction[] active;
                synchronized (transactions) {
                    active = transactions.toArray(PostgresTransaction[]::new);
                }
                for (PostgresTransaction transaction : active) transaction.abort();
                dataSource.close();
            } finally {
                lifecycle.writeLock().unlock();
            }
        }

        private void ensureOpen() {
            if (closed || dataSource.isClosed()) throw new DatabaseFailure("Connection is closed.");
        }

        private void transactionFinished(PostgresTransaction transaction) {
            synchronized (transactions) {
                transactions.remove(transaction);
            }
        }
    }

    private static final class PostgresTransaction implements DatabaseTransaction {
        private final PooledPostgresConnection owner;
        private final Connection connection;
        private final int timeoutSeconds;
        private boolean closed;

        private PostgresTransaction(
                PooledPostgresConnection owner, Connection connection, int timeoutSeconds) {
            this.owner = owner;
            this.connection = connection;
            this.timeoutSeconds = timeoutSeconds;
        }

        @Override
        public synchronized List<Object> query(String sql, List<?> parameters) {
            ensureActive();
            try {
                return JdbcQueries.query(connection, sql, parameters, timeoutSeconds,
                    SqlParameters.Dialect.POSTGRESQL, "PostgreSQL", DatabaseErrors::postgres);
            } catch (DatabaseFailure failure) {
                abort();
                throw failure;
            }
        }

        @Override
        public synchronized int execute(String sql, List<?> parameters) {
            ensureActive();
            try {
                return JdbcQueries.execute(connection, sql, parameters, timeoutSeconds,
                    SqlParameters.Dialect.POSTGRESQL, DatabaseErrors::postgres);
            } catch (DatabaseFailure failure) {
                abort();
                throw failure;
            }
        }

        @Override
        public int lastInsertId() {
            throw new DatabaseFailure(
                "lastInsertId() is SQLite-only; use an INSERT ... RETURNING query with PostgreSQL.");
        }

        @Override
        public synchronized void commit() {
            finish(true);
        }

        @Override
        public synchronized void rollback() {
            finish(false);
        }

        @Override
        public synchronized void abort() {
            if (closed) return;
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Preserve the triggering failure while ensuring the pooled connection is closed.
            } finally {
                closePhysical();
            }
        }

        private void finish(boolean commit) {
            ensureActive();
            try {
                if (commit) connection.commit(); else connection.rollback();
            } catch (SQLException error) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                throw DatabaseErrors.postgres(error);
            } finally {
                closePhysical();
            }
        }

        private void closePhysical() {
            if (closed) return;
            closed = true;
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Closing a failed pooled connection is best effort.
            } finally {
                owner.transactionFinished(this);
            }
        }

        private void ensureActive() {
            if (closed) throw new DatabaseFailure("Transaction is closed.");
        }

        @Override
        public synchronized boolean isClosed() {
            return closed;
        }
    }
}
