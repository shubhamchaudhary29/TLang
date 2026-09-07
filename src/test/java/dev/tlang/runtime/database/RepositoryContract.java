package dev.tlang.runtime.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** The same repository contract executes against both real providers. */
public abstract class RepositoryContract {
    protected abstract DatabaseConnection open();
    protected static Map<String, Object> descriptor() {
        return Map.of("primaryKey", "id", "fields", List.of("id", "name", "active", "day", "created"),
            "readOnly", List.of("day", "created"));
    }

    private DatabaseConnection setup() {
        var connection = open();
        connection.execute("DROP TABLE IF EXISTS repo_users", List.of());
        connection.execute("CREATE TABLE repo_users (id INTEGER PRIMARY KEY, name TEXT UNIQUE, active BOOLEAN, "
            + "day DATE DEFAULT CURRENT_DATE, created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, password_hash TEXT DEFAULT 'hidden')", List.of());
        return connection;
    }

    private static DatabaseRepository users(DatabaseSession session) {
        return DatabaseRepository.define(session, "repo_users", descriptor());
    }

    @Test void crudReturnsStableFieldsAndExistingValueConversions() {
        var connection = setup();
        try {
            var repository = users(connection);
            assertEquals(0, repository.count());
            assertNull(repository.find(42));
            assertFalse(repository.exists(42));
            assertEquals(1, repository.create(Map.of("id", 42, "name", "Ada", "active", true)));
            assertEquals(1, repository.count());
            assertTrue(repository.exists(42));
            Map<?, ?> row = (Map<?, ?>) repository.find(42);
            assertEquals(List.of("id", "name", "active", "day", "created"), new ArrayList<>(row.keySet()));
            assertEquals("Ada", row.get("name"));
            assertEquals(connection.providerName().equals("sqlite") ? 1 : true, row.get("active"));
            assertInstanceOf(String.class, row.get("day"));
            assertInstanceOf(String.class, row.get("created"));
            assertFalse(row.containsKey("password_hash"));
            connection.execute("ALTER TABLE repo_users ADD COLUMN reset_token TEXT DEFAULT 'also-hidden'", List.of());
            assertEquals(row.keySet(), ((Map<?, ?>) repository.find(42)).keySet());
            assertEquals(row.keySet(), ((Map<?, ?>) repository.query().all(connection).getFirst()).keySet());
            var nullable = new HashMap<String, Object>(); nullable.put("name", null); nullable.put("active", false);
            assertEquals(1, repository.update(42, nullable));
            assertNull(((Map<?, ?>) repository.find(42)).get("name"));
            assertEquals(0, repository.update(999, Map.of("name", "missing")));
            assertEquals(0, repository.delete(999));
            assertEquals(1, repository.delete(42));
            assertEquals(0, repository.delete(42));
            assertFalse(repository.exists(42));
        } finally { connection.close(); }
    }

    @Test void queryEscapeUsesM3AndExistsOnlyReadsPrimaryKey() {
        var connection = setup();
        try {
            var repository = users(connection);
            for (int i = 1; i <= 4; i++) repository.create(Map.of("id", i, "name", "name-" + i));
            var base = repository.query();
            var active = base.where("id", ">", 2);
            var recent = base.orderBy("id", "desc").limit(1);
            assertEquals(2, active.count(connection));
            assertEquals(4, base.count(connection));
            assertEquals(4, ((Map<?, ?>) recent.first(connection)).get("id"));
            assertEquals(4, repository.query().all(connection).size());
            assertNotSame(base, repository.query());
            assertEquals(List.of(Map.of("password_hash", "hidden")),
                base.select(List.of("password_hash")).limit(1).all(connection));
            assertThrows(DatabaseFailure.class, () -> base.where("id", "=", 1).update(connection, Map.of("name", "no")));
            var missingProjection = DatabaseRepository.define(connection, "repo_users",
                Map.of("primaryKey", "id", "fields", List.of("id", "missing_column")));
            assertTrue(missingProjection.exists(1));
            assertThrows(DatabaseFailure.class, () -> missingProjection.find(1));
        } finally { connection.close(); }
    }

    @Test void mutationsAndInvalidIdsFailWithoutChangingRows() {
        var connection = setup();
        try {
            var repository = users(connection);
            repository.create(Map.of("id", 1, "name", "unique"));
            for (Object invalid : Arrays.asList(null, "name", List.of(), Map.of(), Map.of("unknown", 1),
                    Map.of("created", "tomorrow"), Map.of("CREATED", "tomorrow"), Map.of("name", List.of(1)))) {
                assertThrows(DatabaseFailure.class, () -> repository.create(invalid));
                assertThrows(DatabaseFailure.class, () -> repository.update(1, invalid));
            }
            assertThrows(DatabaseFailure.class, () -> repository.update(1, Map.of("id", 2)));
            for (Object invalid : Arrays.asList(null, List.of(1), Map.of("id", 1), 1L)) {
                assertThrows(DatabaseFailure.class, () -> repository.find(invalid));
                assertThrows(DatabaseFailure.class, () -> repository.exists(invalid));
                assertThrows(DatabaseFailure.class, () -> repository.update(invalid, Map.of("name", "changed")));
                assertThrows(DatabaseFailure.class, () -> repository.delete(invalid));
            }
            assertThrows(DatabaseFailure.class, () -> repository.create(Map.of("id", 2, "name", "unique")));
            assertThrows(DatabaseFailure.class, () -> repository.create(Map.of("id", 1, "name", "other")));
            assertEquals(1, repository.count());
            assertEquals("unique", ((Map<?, ?>) repository.find(1)).get("name"));
            assertThrows(DatabaseFailure.class, () -> DatabaseRepository.define(connection, "repo_missing", descriptor()).count());
        } finally { connection.close(); }
    }

    @Test void hostileStringIdsAndValuesRemainBoundParameters() {
        var connection = open();
        try {
            connection.execute("DROP TABLE IF EXISTS repo_strings", List.of());
            connection.execute("CREATE TABLE repo_strings (key TEXT PRIMARY KEY, value TEXT)", List.of());
            var repository = DatabaseRepository.define(connection, "repo_strings",
                Map.of("primaryKey", "key", "fields", List.of("key", "value")));
            for (String value : List.of("' OR 1=1 --", "'; DROP TABLE repo_strings; --", "quotes \"\n🚀", "", "x".repeat(100_000))) {
                assertEquals(1, repository.create(Map.of("key", value, "value", value)));
                assertTrue(repository.exists(value));
                assertEquals(value, ((Map<?, ?>) repository.find(value)).get("value"));
                assertEquals(1, repository.update(value, Map.of("value", "changed")));
                assertEquals(1, repository.delete(value));
            }
            assertEquals(0, repository.count());
        } finally { connection.close(); }
    }

    @Test void transactionsCommitRollbackUpdateDeleteAndIsolate() {
        var connection = setup();
        try {
            var tx = connection.begin();
            var repository = users(tx);
            repository.create(Map.of("id", 1, "name", "first"));
            repository.create(Map.of("id", 2, "name", "second"));
            assertEquals(1, repository.update(1, Map.of("name", "updated")));
            assertEquals(1, repository.delete(2));
            assertTrue(repository.exists(1));
            assertEquals(1, repository.query().all(tx).size());
            if (connection.providerName().equals("postgresql")) assertEquals(0, users(connection).count());
            else assertThrows(DatabaseFailure.class, () -> users(connection).count());
            tx.commit();
            assertThrows(DatabaseFailure.class, () -> repository.find(1));
            assertThrows(DatabaseFailure.class, repository::query);
            assertEquals("updated", ((Map<?, ?>) users(connection).find(1)).get("name"));
            var rollback = connection.begin();
            var rolled = users(rollback);
            rolled.create(Map.of("id", 2)); rolled.delete(1);
            rollback.rollback();
            assertThrows(DatabaseFailure.class, () -> rolled.exists(1));
            assertEquals(1, users(connection).count());
        } finally { connection.close(); }
    }

    @Test void validationAndDatabaseFailuresAbortTransactions() {
        var connection = setup();
        try {
            List<Consumer<DatabaseRepository>> failures = List.of(
                r -> r.create(Map.of()), r -> r.create(Map.of("unknown", 1)),
                r -> r.create(Map.of("created", "bad")), r -> r.update(1, Map.of("id", 2)),
                r -> r.update(1, Map.of()), r -> r.delete(null), r -> r.find(List.of(1)),
                r -> r.exists(null), r -> r.create(Map.of("id", 2, "name", "same")));
            for (var failure : failures) {
                var tx = connection.begin();
                var repository = users(tx);
                repository.create(Map.of("id", 1, "name", "same"));
                assertThrows(DatabaseFailure.class, () -> failure.accept(repository));
                assertTrue(tx.isClosed());
                assertThrows(DatabaseFailure.class, tx::commit);
                assertEquals(0, users(connection).count());
            }
            var malformed = connection.begin();
            users(malformed).create(Map.of("id", 1));
            assertThrows(DatabaseFailure.class, () -> DatabaseRepository.define(malformed, "repo_users", Map.of()));
            assertTrue(malformed.isClosed());
            assertEquals(0, users(connection).count());
        } finally { connection.close(); }
    }

    @Test void closedOwnersInvalidateRepositoryAndDerivedQueries() {
        var connection = setup();
        var repository = users(connection);
        var query = repository.query();
        var tx = connection.begin();
        var transactionRepository = users(tx);
        transactionRepository.create(Map.of("id", 1));
        connection.close();
        assertTrue(tx.isClosed());
        for (DatabaseRepository closed : List.of(repository, transactionRepository)) {
            assertThrows(DatabaseFailure.class, () -> closed.find(1));
            assertThrows(DatabaseFailure.class, () -> closed.exists(1));
            assertThrows(DatabaseFailure.class, () -> closed.create(Map.of("id", 1)));
            assertThrows(DatabaseFailure.class, () -> closed.update(1, Map.of("name", "bad")));
            assertThrows(DatabaseFailure.class, () -> closed.delete(1));
            assertThrows(DatabaseFailure.class, closed::count);
            assertThrows(DatabaseFailure.class, closed::query);
        }
        assertThrows(DatabaseFailure.class, () -> query.all(connection));
        assertThrows(DatabaseFailure.class, () -> users(connection));
    }

    @Test void concurrentCreatesFindExistsAndIndependentQueries() throws Exception {
        var connection = setup();
        var executor = Executors.newFixedThreadPool(16);
        try {
            var fields = new ArrayList<>(List.of("id", "name", "active"));
            var supplied = new HashMap<String, Object>(Map.of("fields", fields, "primaryKey", "id"));
            var repository = DatabaseRepository.define(connection, "repo_users", supplied);
            fields.clear(); supplied.clear();
            repository.create(Map.of("id", -1, "name", "stable"));
            List<Callable<Integer>> operations = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                int id = i;
                operations.add(() -> {
                    assertTrue(repository.exists(-1));
                    assertEquals("stable", ((Map<?, ?>) repository.find(-1)).get("name"));
                    assertEquals(1, repository.create(Map.of("id", id, "name", "name-" + id)));
                    assertTrue(repository.exists(id));
                    assertEquals(id, ((Map<?, ?>) repository.find(id)).get("id"));
                    var base = repository.query();
                    var derived = base.where("id", "=", id);
                    assertEquals(1, derived.count(connection));
                    assertTrue(base.count(connection) >= 2);
                    assertEquals(-1, ((Map<?, ?>) base.orderBy("id", "asc").first(connection)).get("id"));
                    return repository.update(id, Map.of("active", true));
                });
            }
            for (var result : executor.invokeAll(operations)) assertEquals(1, result.get(20, TimeUnit.SECONDS));
            assertEquals(121, repository.count());
            assertEquals(120, repository.query().where("active", "=", true).count(connection));
        } finally {
            executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            connection.close();
        }
    }

    @Test void readOnlyPrimaryKeyMayUseDatabaseDefaultButCannotBeSupplied() {
        var connection = open();
        try {
            connection.execute("CREATE TABLE repo_defaults (key TEXT PRIMARY KEY DEFAULT 'generated-key', name TEXT)", List.of());
            var repository = DatabaseRepository.define(connection, "repo_defaults",
                Map.of("primaryKey", "key", "fields", List.of("key", "name"), "readOnly", List.of("key")));
            assertEquals(1, repository.create(Map.of("name", "defaulted")));
            assertEquals(Map.of("key", "generated-key", "name", "defaulted"), repository.find("generated-key"));
            assertThrows(DatabaseFailure.class, () -> repository.create(Map.of("key", "other", "name", "bad")));
            assertThrows(DatabaseFailure.class, () -> repository.update("generated-key", Map.of("key", "other")));
        } finally { connection.close(); }
    }

    @Test void migrationsThenRepositoryCrud(@TempDir Path directory) throws Exception {
        var connection = open();
        try {
            connection.execute("DROP TABLE IF EXISTS _tlang_migrations", List.of());
            connection.execute("DROP TABLE IF EXISTS repo_migrated", List.of());
            Files.writeString(directory.resolve("0001_create.sql"), "CREATE TABLE repo_migrated (id INTEGER PRIMARY KEY, name TEXT);");
            DatabaseMigrations.migrate(connection, directory.toString());
            var repository = DatabaseRepository.define(connection, "repo_migrated",
                Map.of("primaryKey", "id", "fields", List.of("id", "name")));
            assertEquals(1, repository.create(Map.of("id", 1, "name", "migrated")));
            assertEquals(Map.of("id", 1, "name", "migrated"), repository.find(1));
            repository.update(1, Map.of("name", "updated"));
            assertEquals(1, repository.delete(1));
        } finally { connection.close(); }
    }
}
