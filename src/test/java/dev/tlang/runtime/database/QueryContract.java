package dev.tlang.runtime.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Identical real-database scenarios inherited by SQLite and Testcontainers PostgreSQL. */
public abstract class QueryContract {
    protected abstract DatabaseConnection open();
    private static final DatabaseQuery USERS = DatabaseQuery.table("qb_users");

    private DatabaseConnection setup() {
        DatabaseConnection connection = open();
        connection.execute("DROP TABLE IF EXISTS qb_users", List.of());
        connection.execute("CREATE TABLE qb_users (id INTEGER PRIMARY KEY, name TEXT UNIQUE, active BOOLEAN, "
            + "day DATE DEFAULT CURRENT_DATE, created TIMESTAMP DEFAULT CURRENT_TIMESTAMP)", List.of());
        return connection;
    }

    @Test void completeCrudFilteringPaginationTypesAndEmptyResults() {
        DatabaseConnection connection = setup();
        try {
            assertNull(USERS.first(connection));
            assertEquals(0, USERS.count(connection));
            for (int i = 1; i <= 5; i++) assertEquals(1, USERS.insert(connection, Map.of("id", i, "name", "name-" + i, "active", true)));
            var nullable = new java.util.HashMap<String, Object>();
            nullable.put("id", 6); nullable.put("name", null); nullable.put("active", false);
            USERS.insert(connection, nullable);
            assertEquals(6, USERS.count(connection));
            assertEquals(3, USERS.where("id", ">", 2).where("id", "<=", 5).count(connection));
            assertEquals(2, USERS.where("id", "<", 3).count(connection));
            assertEquals(5, USERS.where("id", "!=", 6).count(connection));
            assertEquals(5, USERS.where("name", "like", "name-%").count(connection));
            assertEquals(5, USERS.where("active", "=", true).count(connection));
            assertEquals(1, USERS.where("name", "=", null).count(connection));
            assertEquals(5, USERS.where("name", "!=", null).count(connection));
            assertEquals(2, USERS.whereIn("name", Arrays.asList("name-1", null)).count(connection));
            assertEquals(0, USERS.whereIn("id", List.of()).count(connection));
            assertEquals(0, USERS.whereIn("id", List.of()).update(connection, Map.of("name", "unused")));
            assertEquals(0, USERS.whereIn("id", List.of()).delete(connection));
            var page = USERS.where("id", ">=", 2).select(List.of("id", "name")).orderBy("id", "desc").limit(2).offset(1);
            assertEquals(List.of(Map.of("id", 5, "name", "name-5"), Map.of("id", 4, "name", "name-4")), page.all(connection));
            assertEquals(5, page.count(connection));
            assertEquals(5, row(page.first(connection)).get("id"));
            assertEquals(5, USERS.offset(1).all(connection).size());
            assertEquals(6, USERS.offset(0).all(connection).size());
            assertEquals(List.of(), USERS.limit(0).all(connection));
            assertNull(USERS.limit(0).first(connection));
            assertNull(USERS.where("id", "=", 999).first(connection));
            assertNotNull(row(USERS.first(connection)).get("day"));
            assertInstanceOf(String.class, row(USERS.first(connection)).get("created"));
            assertEquals(1, USERS.where("id", "=", 1).update(connection, Map.of("name", "updated")));
            assertEquals("updated", row(USERS.where("id", "=", 1).first(connection)).get("name"));
            assertEquals(1, USERS.where("id", "=", 1).delete(connection));
            assertEquals(0, USERS.where("id", "=", 1).delete(connection));
        } finally { connection.close(); }
    }

    @Test void failuresSafetyInjectionAndRecovery() {
        DatabaseConnection connection = setup();
        try {
            for (String value : List.of("' OR 1=1; DROP TABLE qb_users; --", "\"quoted\"\n🚀", "", "x".repeat(100_000))) {
                USERS.insert(connection, Map.of("id", USERS.count(connection), "name", value));
                assertEquals(1, USERS.where("name", "=", value).count(connection));
            }
            DatabaseFailure duplicate = assertThrows(DatabaseFailure.class,
                () -> USERS.insert(connection, Map.of("id", 0, "name", "secret-credential-value")));
            assertFalse(duplicate.getMessage().contains("secret-credential-value"));
            assertFalse(duplicate.getMessage().contains("org.postgresql"));
            assertThrows(DatabaseFailure.class, () -> DatabaseQuery.table("missing_qb_table").all(connection));
            // A missing predicate column is an error even on SQLite with double-quoted string compatibility.
            assertThrows(DatabaseFailure.class, () -> USERS.where("missing_column", "=", 1).all(connection));
            assertThrows(DatabaseFailure.class, () -> USERS.update(connection, Map.of("name", "bad")));
            assertThrows(DatabaseFailure.class, () -> USERS.delete(connection));
            assertThrows(DatabaseFailure.class, () -> USERS.insert(connection, Map.of()));
            assertEquals(4, USERS.count(connection));
            assertEquals(1, USERS.whereIn("id", java.util.Collections.nCopies(900, 0)).count(connection));
        } finally { connection.close(); }
        assertThrows(DatabaseFailure.class, () -> USERS.all(connection));
        assertThrows(DatabaseFailure.class, () -> USERS.insert(connection, Map.of("id", 8)));
    }

    @Test void transactionCommitRollbackFailureAndIsolation() {
        DatabaseConnection connection = setup();
        try {
            DatabaseTransaction committed = connection.begin();
            USERS.insert(committed, Map.of("id", 1, "name", "committed"));
            assertEquals(1, USERS.count(committed));
            committed.commit();
            assertThrows(DatabaseFailure.class, () -> USERS.all(committed));
            DatabaseTransaction rolledBack = connection.begin();
            USERS.insert(rolledBack, Map.of("id", 2, "name", "rolled-back"));
            rolledBack.rollback();
            assertThrows(DatabaseFailure.class, () -> USERS.delete(rolledBack));
            assertEquals(1, USERS.count(connection));
            DatabaseTransaction failed = connection.begin();
            USERS.insert(failed, Map.of("id", 2, "name", "discarded"));
            assertThrows(DatabaseFailure.class, () -> USERS.insert(failed, Map.of("id", 3, "name", "committed")));
            assertTrue(failed.isClosed());
            assertThrows(DatabaseFailure.class, failed::commit);
            assertEquals(1, USERS.count(connection));
            DatabaseTransaction invalid = connection.begin();
            USERS.insert(invalid, Map.of("id", 2));
            assertThrows(DatabaseFailure.class, () -> USERS.update(invalid, Map.of("name", "unsafe")));
            assertTrue(invalid.isClosed());
            assertEquals(1, USERS.count(connection));
            DatabaseTransaction isolated = connection.begin();
            USERS.insert(isolated, Map.of("id", 2));
            if (connection.providerName().equals("postgresql")) assertEquals(1, USERS.count(connection));
            else assertThrows(DatabaseFailure.class, () -> USERS.count(connection));
            isolated.rollback();
            DatabaseTransaction closed = connection.begin();
            USERS.insert(closed, Map.of("id", 2));
            connection.close();
            assertTrue(closed.isClosed());
            assertThrows(DatabaseFailure.class, () -> USERS.first(closed));
        } finally { connection.close(); }
    }

    @Test void concurrentImmutableReuseAndWrites() throws Exception {
        DatabaseConnection connection = setup();
        var executor = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Integer>> inserts = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int id = i;
                inserts.add(() -> USERS.insert(connection, Map.of("id", id, "name", "value-" + id)));
            }
            for (var future : executor.invokeAll(inserts)) assertEquals(1, future.get(15, TimeUnit.SECONDS));
            DatabaseQuery all = USERS.orderBy("id", "asc");
            List<Callable<Integer>> reads = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                int id = i % 100;
                reads.add(() -> {
                    assertEquals(100, all.all(connection).size());
                    assertEquals(100, USERS.count(connection));
                    assertEquals(1, all.where("id", "=", id).count(connection));
                    assertEquals(id, row(all.whereIn("id", List.of(id)).first(connection)).get("id"));
                    return id;
                });
            }
            for (var future : executor.invokeAll(reads)) assertNotNull(future.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            connection.close();
        }
    }

    @Test void migrationCreatedTable(@TempDir Path directory) throws Exception {
        DatabaseConnection connection = open();
        try {
            connection.execute("DROP TABLE IF EXISTS _tlang_migrations", List.of());
            connection.execute("DROP TABLE IF EXISTS qb_migrated", List.of());
            Files.writeString(directory.resolve("0001_users.sql"), "CREATE TABLE qb_migrated (id INTEGER PRIMARY KEY, name TEXT);");
            DatabaseMigrations.migrate(connection, directory.toString());
            var query = DatabaseQuery.table("qb_migrated");
            query.insert(connection, Map.of("id", 1, "name", "migrated"));
            assertEquals("migrated", row(query.first(connection)).get("name"));
        } finally { connection.close(); }
    }

    private static Map<?, ?> row(Object value) { return (Map<?, ?>) value; }
}
