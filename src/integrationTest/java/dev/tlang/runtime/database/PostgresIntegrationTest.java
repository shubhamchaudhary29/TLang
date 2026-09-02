package dev.tlang.runtime.database;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
final class PostgresIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine")
        .withDatabaseName("tlang")
        .withUsername("tlang")
        .withPassword("tlang-test-password");

    @Test
    void authenticatedPostgresqlUrlOpensWithoutSeparateCredentials() {
        String authenticatedTarget = "postgresql://" + POSTGRES.getUsername() + ":"
            + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":"
            + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName();
        DatabaseConnection connection = new PostgresProvider().open(new DatabaseOptions(
            authenticatedTarget, null, null, 2, 5_000, 5));
        try {
            assertEquals(1, scalar(connection, "SELECT 1 AS value"));
        } finally {
            connection.close();
        }
    }

    @Test
    void crudParametersReturningAndTypeMapping() {
        DatabaseConnection connection = open(8, 5_000, 5);
        try {
            assertEquals("postgresql", connection.providerName());
            connection.execute("""
                CREATE TABLE type_values (
                    id SERIAL PRIMARY KEY,
                    text_value TEXT NOT NULL,
                    nullable_value TEXT,
                    bool_value BOOLEAN NOT NULL,
                    int_value INTEGER NOT NULL,
                    date_value DATE NOT NULL,
                    timestamp_value TIMESTAMP NOT NULL,
                    timestamp_tz TIMESTAMPTZ NOT NULL
                )
                """, List.of());

            List<String> values = List.of(
                "' OR true; DROP TABLE type_values; --",
                "quotes ' \" ;",
                "unicode नमस्ते 🌍",
                "line one\nline two",
                "",
                "large-" + "x".repeat(200_000));
            for (int index = 0; index < values.size(); index++) {
                List<Object> parameters = java.util.Arrays.asList(
                    values.get(index), null, index % 2 == 0, -100 + index,
                    "2026-09-02", "2026-09-02 12:34:56", "2026-09-02 12:34:56+00");
                assertEquals(1, connection.execute(
                    "INSERT INTO type_values(text_value, nullable_value, bool_value, int_value, "
                        + "date_value, timestamp_value, timestamp_tz) VALUES (?, ?, ?, ?, CAST(? AS DATE), "
                        + "CAST(? AS TIMESTAMP), CAST(? AS TIMESTAMPTZ))",
                    parameters));
            }

            List<Object> rows = connection.query(
                "SELECT id, text_value, nullable_value, bool_value, int_value, date_value, timestamp_value, "
                    + "timestamp_tz "
                    + "FROM type_values ORDER BY id", List.of());
            assertEquals(values.size(), rows.size());
            for (int index = 0; index < rows.size(); index++) {
                Map<String, Object> row = row(rows.get(index));
                assertEquals(values.get(index), row.get("text_value"));
                assertNull(row.get("nullable_value"));
                assertEquals(index % 2 == 0, row.get("bool_value"));
                assertEquals(-100 + index, row.get("int_value"));
                assertEquals("2026-09-02", row.get("date_value"));
                assertEquals("2026-09-02T12:34:56", row.get("timestamp_value"));
                assertEquals("2026-09-02T12:34:56Z", row.get("timestamp_tz"));
            }

            List<Object> returned = connection.query(
                "INSERT INTO type_values(text_value, nullable_value, bool_value, int_value, date_value, timestamp_value, "
                    + "timestamp_tz) VALUES (?, ?, ?, ?, CAST(? AS DATE), CAST(? AS TIMESTAMP), "
                    + "CAST(? AS TIMESTAMPTZ)) RETURNING id",
                java.util.Arrays.asList("returned", null, true, Integer.MAX_VALUE,
                    "2026-09-02", "2026-09-02 00:00:00", "2026-09-02 00:00:00+00"));
            assertEquals(1, returned.size());
            assertInstanceOf(Integer.class, row(returned.getFirst()).get("id"));
            assertThrows(DatabaseFailure.class, connection::lastInsertId);

            assertEquals(1, connection.execute(
                "UPDATE type_values SET text_value = ? WHERE text_value = ?", List.of("updated", "returned")));
            assertEquals(1, connection.execute(
                "DELETE FROM type_values WHERE text_value = ?", List.of("updated")));
            assertEquals(values.size(), scalar(connection, "SELECT count(*) AS value FROM type_values"));
        } finally {
            connection.close();
        }
    }

    @Test
    void transactionsCommitRollbackFailureRecoveryAndIsolation() {
        DatabaseConnection connection = open(4, 5_000, 5);
        try {
            connection.execute("CREATE TABLE transaction_values (id INTEGER PRIMARY KEY, value TEXT UNIQUE)", List.of());

            DatabaseTransaction first = connection.begin();
            first.execute("INSERT INTO transaction_values VALUES (?, ?)", List.of(1, "commit"));
            first.commit();

            DatabaseTransaction second = connection.begin();
            second.execute("INSERT INTO transaction_values VALUES (?, ?)", List.of(2, "rollback"));
            second.rollback();

            DatabaseTransaction simultaneousA = connection.begin();
            DatabaseTransaction simultaneousB = connection.begin();
            simultaneousA.execute("INSERT INTO transaction_values VALUES (?, ?)", List.of(3, "a"));
            simultaneousB.execute("INSERT INTO transaction_values VALUES (?, ?)", List.of(4, "b"));
            simultaneousA.commit();
            simultaneousB.commit();

            DatabaseTransaction failed = connection.begin();
            DatabaseFailure unique = assertThrows(DatabaseFailure.class,
                () -> failed.execute("INSERT INTO transaction_values VALUES (?, ?)", List.of(5, "commit")));
            assertEquals("Unique constraint violation.", unique.getMessage());
            assertTrue(failed.isClosed(), "statement failures must auto-rollback and return the pool resource");

            assertEquals(1, connection.execute(
                "INSERT INTO transaction_values VALUES (?, ?)", List.of(5, "reused")));
            assertEquals(4, scalar(connection, "SELECT count(*) AS value FROM transaction_values"));
        } finally {
            connection.close();
        }
    }

    @Test
    void structuredSqlErrorsAndUnsupportedTypesAreDeterministic() {
        DatabaseConnection connection = open(2, 5_000, 5);
        try {
            connection.execute("CREATE TABLE constraints_test (id INTEGER PRIMARY KEY, parent_id INTEGER "
                + "REFERENCES constraints_test(id), "
                + "unique_value TEXT UNIQUE, checked INTEGER CHECK (checked > 0))", List.of());
            assertEquals("Unique constraint violation.", failure(() -> {
                connection.execute("INSERT INTO constraints_test VALUES (1, NULL, 'same', 1)", List.of());
                connection.execute("INSERT INTO constraints_test VALUES (2, NULL, 'same', 1)", List.of());
            }).getMessage());
            assertEquals("Check constraint violation.", failure(() -> connection.execute(
                "INSERT INTO constraints_test VALUES (3, NULL, 'check', -1)", List.of())).getMessage());
            assertEquals("Foreign key constraint violation.", failure(() -> connection.execute(
                "INSERT INTO constraints_test VALUES (4, 999, 'foreign', 1)", List.of())).getMessage());
            assertEquals("Database table does not exist.", failure(() -> connection.query(
                "SELECT * FROM table_that_does_not_exist", List.of())).getMessage());
            assertEquals("Invalid SQL statement.", failure(() -> connection.execute(
                "SELECT FROM", List.of())).getMessage());
            assertEquals("Expected 1 parameters, but got 0.", failure(() -> connection.query(
                "SELECT ?", List.of())).getMessage());
            assertTrue(failure(() -> connection.query(
                "SELECT 2147483648::BIGINT AS value", List.of())).getMessage().contains("out-of-range"));
            assertTrue(failure(() -> connection.query(
                "SELECT 1.5::NUMERIC AS value", List.of())).getMessage().contains("fractional"));
            assertTrue(failure(() -> connection.query(
                "SELECT '{}'::JSONB AS value", List.of())).getMessage().contains("Unsupported"));
            assertTrue(failure(() -> connection.query(
                "SELECT 1 AS duplicate, 2 AS duplicate", List.of())).getMessage().contains("duplicate"));
            assertTrue(connection.query("SELECT 1 AS value WHERE false", List.of()).isEmpty());
            assertEquals(1, scalar(connection, "SELECT 1 AS value"));
        } finally {
            connection.close();
        }
    }

    @Test
    void concurrentReadsWritesAndMixedLoadPreserveAllRows() throws Exception {
        DatabaseConnection connection = open(12, 5_000, 5);
        var executor = Executors.newFixedThreadPool(24);
        try {
            connection.execute("CREATE TABLE stress_values (id INTEGER PRIMARY KEY, value TEXT NOT NULL)", List.of());
            List<Callable<Integer>> inserts = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                int id = index;
                inserts.add(() -> connection.execute(
                    "INSERT INTO stress_values VALUES (?, ?)", List.of(id, "value-" + id)));
            }
            for (var future : executor.invokeAll(inserts)) assertEquals(1, future.get());
            assertEquals(100, scalar(connection, "SELECT count(*) AS value FROM stress_values"));

            List<Callable<Integer>> mixed = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                int id = index;
                mixed.add(() -> row(connection.query(
                    "SELECT id FROM stress_values WHERE id = ?", List.of(id)).getFirst()).get("id").equals(id) ? 1 : 0);
                mixed.add(() -> connection.execute(
                    "UPDATE stress_values SET value = ? WHERE id = ?", List.of("updated-" + id, id)));
            }
            for (var future : executor.invokeAll(mixed)) assertEquals(1, future.get());
            assertEquals(100, scalar(connection,
                "SELECT count(*) AS value FROM stress_values WHERE value LIKE 'updated-%'"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            connection.close();
        }
    }

    @Test
    void boundedPoolExhaustionAndQueryTimeoutFailPromptlyAndRecover() throws Exception {
        DatabaseConnection connection = open(2, 300, 1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            DatabaseTransaction first = connection.begin();
            DatabaseTransaction second = connection.begin();
            var waiting = executor.submit(() -> connection.query("SELECT 1 AS value", List.of()));
            ExecutionException exhausted = assertThrows(ExecutionException.class,
                () -> waiting.get(3, TimeUnit.SECONDS));
            assertInstanceOf(DatabaseFailure.class, exhausted.getCause());
            assertEquals("Timed out waiting for a database connection.", exhausted.getCause().getMessage());
            first.rollback();
            second.rollback();
            assertEquals(1, scalar(connection, "SELECT 1 AS value"));

            DatabaseFailure timeout = assertTimeoutPreemptively(Duration.ofSeconds(4),
                () -> failure(() -> connection.query("SELECT 1 AS value FROM pg_sleep(2)", List.of())));
            assertEquals("Database query timed out.", timeout.getMessage());
            assertEquals(1, scalar(connection, "SELECT 1 AS value"));
        } finally {
            executor.shutdownNow();
            connection.close();
        }
    }

    @Test
    void killedConnectionIsDiscardedAndPoolRecovers() {
        DatabaseConnection connection = open(3, 5_000, 5);
        try {
            DatabaseTransaction victim = connection.begin();
            int backendPid = (Integer) row(victim.query(
                "SELECT pg_backend_pid() AS pid", List.of()).getFirst()).get("pid");
            assertEquals(true, row(connection.query(
                "SELECT pg_terminate_backend(?) AS terminated", List.of(backendPid)).getFirst()).get("terminated"));
            assertThrows(DatabaseFailure.class,
                () -> victim.query("SELECT 1 AS value", List.of()));
            assertTrue(victim.isClosed());
            assertEquals(1, scalar(connection, "SELECT 1 AS value"));
        } finally {
            connection.close();
        }
    }

    @Test
    void authenticationFailuresAndUrlCredentialsNeverLeakSecrets() {
        String secret = "SUPER_SECRET_PASSWORD";
        DatabaseOptions options = new DatabaseOptions(
            target(), POSTGRES.getUsername(), secret, 1, 500, 2);
        DatabaseFailure failure = assertThrows(DatabaseFailure.class,
            () -> new PostgresProvider().open(options));
        assertEquals("Database authentication failed.", failure.getMessage());
        Throwable current = failure;
        while (current != null) {
            assertFalse(String.valueOf(current.getMessage()).contains(secret));
            assertFalse(current.toString().contains(secret));
            current = current.getCause();
        }
    }

    @Test
    void repeatedOpenCloseAndCloseWithTransactionDoNotLeakResources() {
        for (int iteration = 0; iteration < 20; iteration++) {
            DatabaseConnection connection = open(2, 5_000, 5);
            assertEquals(1, scalar(connection, "SELECT 1 AS value"));
            if (iteration == 10) {
                DatabaseTransaction transaction = connection.begin();
                transaction.query("SELECT 1 AS value", List.of());
                connection.close();
                assertTrue(transaction.isClosed());
            } else {
                connection.close();
            }
            assertTrue(connection.isClosed());
            assertDoesNotThrow(connection::close);
            assertThrows(DatabaseFailure.class,
                () -> connection.query("SELECT 1 AS value", List.of()));
        }
    }

    private static DatabaseConnection open(int poolSize, int connectionTimeoutMs, int queryTimeoutSeconds) {
        return new PostgresProvider().open(new DatabaseOptions(
            target(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            poolSize, connectionTimeoutMs, queryTimeoutSeconds));
    }

    private static String target() {
        return "postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
            + "/" + POSTGRES.getDatabaseName();
    }

    private static int scalar(DatabaseConnection connection, String sql) {
        return (Integer) row(connection.query(sql, List.of()).getFirst()).get("value");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Object value) {
        return (Map<String, Object>) value;
    }

    private static DatabaseFailure failure(Runnable action) {
        return assertThrows(DatabaseFailure.class, action::run);
    }
}
