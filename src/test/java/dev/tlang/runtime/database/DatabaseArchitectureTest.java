package dev.tlang.runtime.database;

import dev.tlang.errors.ErrorFormatter;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.interpreter.RuntimeCollections;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import dev.tlang.modules.DatabaseModule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class DatabaseArchitectureTest {
    private static final DatabaseOptions SQLITE_OPTIONS = new DatabaseOptions(
        ":memory:", null, null, 10, 5_000, 30);

    @Test
    void sqliteCrudAndHostileParametersRemainPreparedValues() {
        DatabaseConnection connection = new SqliteProvider().open(SQLITE_OPTIONS);
        try {
            assertEquals(0, connection.execute(
                "CREATE TABLE values_test (id INTEGER PRIMARY KEY, text_value TEXT, "
                    + "nil_value TEXT, bool_value INTEGER, number_value INTEGER)", List.of()));

            List<String> hostile = List.of(
                "' OR 1=1; DROP TABLE values_test; --",
                "quotes ' \" and semicolons ;;;",
                "unicode தமிழ் 🚀",
                "first line\nsecond line",
                "",
                "x".repeat(100_000));
            for (int index = 0; index < hostile.size(); index++) {
                assertEquals(1, connection.execute(
                    "INSERT INTO values_test(text_value, nil_value, bool_value, number_value) "
                        + "VALUES (?, ?, ?, ?)",
                    java.util.Arrays.asList(hostile.get(index), null, index % 2 == 0, Integer.MIN_VALUE + index)));
            }

            List<Object> rows = connection.query(
                "SELECT text_value, nil_value, bool_value, number_value "
                    + "FROM values_test ORDER BY id", List.of());
            assertEquals(hostile.size(), rows.size());
            for (int index = 0; index < hostile.size(); index++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) rows.get(index);
                assertEquals(hostile.get(index), row.get("text_value"));
                assertNull(row.get("nil_value"));
                assertEquals(index % 2 == 0 ? 1 : 0, row.get("bool_value"));
                assertEquals(Integer.MIN_VALUE + index, row.get("number_value"));
            }
            assertEquals(hostile.size(), count(connection, "values_test"));
        } finally {
            connection.close();
        }
    }

    @Test
    void sqliteTransactionsCommitRollbackAutoRollbackAndRecover() {
        DatabaseConnection connection = new SqliteProvider().open(SQLITE_OPTIONS);
        try {
            connection.execute("CREATE TABLE tx_test (id INTEGER PRIMARY KEY, value TEXT UNIQUE)", List.of());

            DatabaseTransaction committed = connection.begin();
            committed.execute("INSERT INTO tx_test(value) VALUES (?)", List.of("committed"));
            assertThrows(DatabaseFailure.class, connection::begin);
            committed.commit();
            assertEquals(1, count(connection, "tx_test"));

            DatabaseTransaction rolledBack = connection.begin();
            rolledBack.execute("INSERT INTO tx_test(value) VALUES (?)", List.of("rolled back"));
            rolledBack.rollback();
            assertEquals(1, count(connection, "tx_test"));

            DatabaseTransaction failed = connection.begin();
            DatabaseFailure constraint = assertThrows(DatabaseFailure.class,
                () -> failed.execute("INSERT INTO tx_test(value) VALUES (?)", List.of("committed")));
            assertTrue(failed.isClosed());
            assertTrue(constraint.getMessage().contains("SQLITE_CONSTRAINT"));
            assertEquals(1, connection.execute(
                "INSERT INTO tx_test(value) VALUES (?)", List.of("recovered")));
            assertEquals(2, count(connection, "tx_test"));

            DatabaseTransaction open = connection.begin();
            open.execute("INSERT INTO tx_test(value) VALUES (?)", List.of("discarded"));
            connection.close();
            assertTrue(open.isClosed());
            assertDoesNotThrow(connection::close);
            assertThrows(DatabaseFailure.class,
                () -> connection.query("SELECT 1", List.of()));
        } finally {
            connection.close();
        }
    }

    @Test
    void oneSqliteHandleIsSafeAcrossConcurrentCallers() throws Exception {
        DatabaseConnection connection = new SqliteProvider().open(SQLITE_OPTIONS);
        var executor = Executors.newFixedThreadPool(16);
        try {
            connection.execute("CREATE TABLE concurrent_test (id INTEGER PRIMARY KEY, value INTEGER)", List.of());
            List<Callable<Integer>> writes = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                int value = index;
                writes.add(() -> connection.execute(
                    "INSERT INTO concurrent_test(value) VALUES (?)", List.of(value)));
            }
            assertTrue(executor.invokeAll(writes).stream().allMatch(future -> {
                try { return future.get() == 1; } catch (Exception error) { return false; }
            }));
            assertEquals(100, count(connection, "concurrent_test"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            connection.close();
        }
    }

    @Test
    void placeholderCountingIgnoresSqlTextAndComments() {
        String sql = "SELECT '?', \"?\", $$?$$, $tag$?$tag$, ? -- ?\n/* ? */";
        assertEquals(1, SqlParameters.countPlaceholders(sql));
        assertEquals(2, SqlParameters.countPlaceholders("SELECT ?, 'it''s ?', ?"));
        assertEquals(1, SqlParameters.countPlaceholders("SELECT json_value ?? 'key', ?"));
    }

    @Test
    void malformedUrlsUnknownOptionsAndCredentialsAreSafe() {
        NativeFunction open = (NativeFunction) new DatabaseModule().getExports().get("open");
        Token token = new Token(TokenType.IDENTIFIER, "open", null, 1);
        Map<String, Object> options = RuntimeCollections.newMap();
        options.put("socketFactory", "hostile.JavaClass");
        RuntimeError unknown = assertThrows(RuntimeError.class,
            () -> open.call(List.of("postgresql://localhost/db", options), token));
        assertEquals("Unknown database option 'socketFactory'.", unknown.getMessage());

        String secret = "SUPER_SECRET_PASSWORD";
        RuntimeError malformed = assertThrows(RuntimeError.class,
            () -> open.call(List.of("postgresql://user:" + secret + "@localhost/%ZZ"), token));
        assertFalse(ErrorFormatter.format(malformed).contains(secret));
        assertThrowableDoesNotContain(malformed, secret);

        assertThrows(RuntimeError.class,
            () -> open.call(List.of("postgresql://localhost:70000/database"), token));
        assertThrows(RuntimeError.class,
            () -> open.call(List.of("postgresql://localhost/database%2Fextra"), token));
        assertThrows(RuntimeError.class,
            () -> open.call(List.of("jdbc:postgresql://localhost/database"), token));

        Map<String, Object> fastFailure = RuntimeCollections.newMap();
        fastFailure.put("connectionTimeoutMs", 250);
        RuntimeError unreachable = assertThrows(RuntimeError.class,
            () -> open.call(List.of("postgresql://user:" + secret + "@127.0.0.1:1/db", fastFailure), token));
        assertFalse(ErrorFormatter.format(unreachable).contains(secret));
        assertThrowableDoesNotContain(unreachable, secret);
    }

    @Test
    void duplicateColumnsAndUnsupportedValuesFailInsteadOfCorruptingRows() {
        DatabaseConnection connection = new SqliteProvider().open(SQLITE_OPTIONS);
        try {
            assertThrows(DatabaseFailure.class,
                () -> connection.query("SELECT 1 AS duplicate, 2 AS duplicate", List.of()));
            assertThrows(DatabaseFailure.class,
                () -> connection.query("SELECT x'0102' AS binary_value", List.of()));
            assertThrows(DatabaseFailure.class,
                () -> connection.query("SELECT 2147483648 AS too_large", List.of()));
            assertThrows(DatabaseFailure.class,
                () -> connection.query("SELECT 1.5 AS fractional", List.of()));
            assertEquals(1, connection.query("SELECT 1 AS valid", List.of()).size());
        } finally {
            connection.close();
        }
    }

    private static int count(DatabaseConnection connection, String table) {
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) connection.query(
            "SELECT count(*) AS count_value FROM " + table, List.of()).getFirst();
        return (Integer) row.get("count_value");
    }

    private static void assertThrowableDoesNotContain(Throwable failure, String secret) {
        Throwable current = failure;
        while (current != null) {
            assertFalse(String.valueOf(current.getMessage()).contains(secret));
            assertFalse(current.toString().contains(secret));
            current = current.getCause();
        }
    }
}
