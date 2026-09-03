package dev.tlang.runtime.database;

import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import dev.tlang.modules.DatabaseModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class SqliteMigrationTest {
    @TempDir Path temporary;

    @Test
    void firstMultipleRerunStatusAndExistingTablesWork() throws Exception {
        Path migrations = directory("basic");
        write(migrations, "0002_seed_users.sql", """
            INSERT INTO users(name) VALUES ('hello; world');
            INSERT INTO users(name) VALUES ('unicode தமிழ் 🚀');
            """);
        write(migrations, "0001_create_users.sql", """
            CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT NOT NULL);
            """);

        try (DatabaseConnection connection = open(":memory:")) {
            connection.execute("CREATE TABLE existing(value TEXT)", List.of());
            assertEquals(List.of("pending", "pending"), states(connection, migrations));
            assertEquals(Map.of("applied", 2, "skipped", 0),
                DatabaseMigrations.migrate(connection, migrations.toString()));
            assertEquals(Map.of("applied", 0, "skipped", 2),
                DatabaseMigrations.migrate(connection, migrations.toString()));
            assertEquals(List.of("applied", "applied"), states(connection, migrations));
            assertEquals(2, scalar(connection, "SELECT count(*) AS value FROM users"));
            assertEquals(2, scalar(connection,
                "SELECT count(*) AS value FROM _tlang_migrations"));
            assertEquals(0, scalar(connection, "SELECT count(*) AS value FROM existing"));
        }
    }

    @Test
    void exactChecksumDriftAndIdentityChangesFailWithoutMutation() throws Exception {
        Path migrations = directory("drift");
        Path first = write(migrations, "0001_create_values.sql",
            "CREATE TABLE values_table(value INTEGER);\nINSERT INTO values_table VALUES (1);\n");
        try (DatabaseConnection connection = open(":memory:")) {
            DatabaseMigrations.migrate(connection, migrations.toString());
            Files.writeString(first,
                "CREATE TABLE changed_table(value INTEGER);\nINSERT INTO values_table VALUES (2);\n");
            DatabaseFailure changed = assertThrows(DatabaseFailure.class,
                () -> DatabaseMigrations.migrate(connection, migrations.toString()));
            assertTrue(changed.getMessage().contains("changed after being applied"));
            assertEquals(1, scalar(connection, "SELECT count(*) AS value FROM values_table"));
            assertEquals(0, scalar(connection, "SELECT count(*) AS value FROM sqlite_master "
                + "WHERE type='table' AND name='changed_table'"));

            Files.move(first, migrations.resolve("0001_renamed.sql"));
            DatabaseFailure identity = assertThrows(DatabaseFailure.class,
                () -> DatabaseMigrations.status(connection, migrations.toString()));
            assertTrue(identity.getMessage().contains("conflicting migration name"));
        }
    }

    @Test
    void failedMultiStatementAndMetadataInsertionRollbackThenRecover() throws Exception {
        Path migrations = directory("rollback");
        Path file = write(migrations, "0001_atomic.sql", """
            CREATE TABLE atomic_values(value INTEGER UNIQUE);
            INSERT INTO atomic_values VALUES (1);
            INSERT INTO atomic_values VALUES (1);
            """);
        try (DatabaseConnection connection = open(":memory:")) {
            DatabaseFailure failure = assertThrows(DatabaseFailure.class,
                () -> DatabaseMigrations.migrate(connection, migrations.toString()));
            assertTrue(failure.getMessage().contains("transaction rolled back"));
            assertEquals(0, scalar(connection, "SELECT count(*) AS value FROM sqlite_master "
                + "WHERE type='table' AND name='atomic_values'"));

            Files.writeString(file, """
                CREATE TABLE atomic_values(value INTEGER UNIQUE);
                INSERT INTO atomic_values VALUES (1);
                INSERT INTO atomic_values VALUES (2);
                """);
            assertEquals(1, DatabaseMigrations.migrate(connection, migrations.toString()).get("applied"));
            assertEquals(2, scalar(connection, "SELECT count(*) AS value FROM atomic_values"));
        }

        Path metadata = directory("metadata");
        write(metadata, "0001_metadata_failure.sql", "CREATE TABLE should_rollback(value INTEGER);");
        try (DatabaseConnection connection = open(":memory:")) {
            connection.execute("""
                CREATE TABLE _tlang_migrations(
                    version INTEGER PRIMARY KEY CHECK(version < 0),
                    name TEXT NOT NULL, checksum TEXT NOT NULL, applied_at TEXT NOT NULL)
                """, List.of());
            assertThrows(DatabaseFailure.class,
                () -> DatabaseMigrations.migrate(connection, metadata.toString()));
            assertEquals(0, scalar(connection, "SELECT count(*) AS value FROM sqlite_master "
                + "WHERE type='table' AND name='should_rollback'"));
        }
    }

    @Test
    void gapsAreAllowedButRetroactiveMigrationsAreRejected() throws Exception {
        Path migrations = directory("ordering");
        write(migrations, "0001_one.sql", "CREATE TABLE one(value INTEGER);");
        write(migrations, "0003_three.sql", "CREATE TABLE three(value INTEGER);");
        try (DatabaseConnection connection = open(":memory:")) {
            DatabaseMigrations.migrate(connection, migrations.toString());
            write(migrations, "0002_two.sql", "CREATE TABLE two(value INTEGER);");
            DatabaseFailure failure = assertThrows(DatabaseFailure.class,
                () -> DatabaseMigrations.migrate(connection, migrations.toString()));
            assertTrue(failure.getMessage().contains("out of order"));
            assertEquals(0, scalar(connection, "SELECT count(*) AS value FROM sqlite_master "
                + "WHERE type='table' AND name='two'"));
        }
    }

    @Test
    void sqliteTriggersHostileStringsAndLargeSqlExecuteCompletely() throws Exception {
        Path migrations = directory("scripts");
        String large = "x".repeat(250_000);
        write(migrations, "0001_scripts.sql", """
            CREATE TABLE source(value TEXT);
            CREATE TABLE audit(value TEXT);
            CREATE TRIGGER source_audit AFTER INSERT ON source
            BEGIN
              INSERT INTO audit(value) VALUES (
                CASE WHEN new.value = 'x;y' THEN 'seen;value' ELSE new.value END);
            END;
            INSERT INTO source(value) VALUES ('x;y');
            INSERT INTO source(value) VALUES (''' OR 1=1; DROP TABLE source; --');
            INSERT INTO source(value) VALUES ('%s');
            """.formatted(large));
        try (DatabaseConnection connection = open(":memory:")) {
            DatabaseMigrations.migrate(connection, migrations.toString());
            assertEquals(3, scalar(connection, "SELECT count(*) AS value FROM source"));
            assertEquals(3, scalar(connection, "SELECT count(*) AS value FROM audit"));
            assertEquals(1, scalar(connection, "SELECT count(*) AS value FROM source "
                + "WHERE length(value) = 250000"));
        }
    }

    @Test
    void separateConnectionsSerializeConcurrentMigrationRuns() throws Exception {
        Path migrations = directory("concurrent");
        write(migrations, "0001_concurrent.sql", """
            CREATE TABLE concurrent_values(value INTEGER);
            INSERT INTO concurrent_values VALUES (1);
            """);
        Path database = temporary.resolve("concurrent.sqlite");
        DatabaseConnection first = open(database.toString());
        DatabaseConnection second = open(database.toString());
        var executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier start = new CyclicBarrier(2);
            Future<Map<String, Object>> a = executor.submit(() -> {
                start.await();
                return DatabaseMigrations.migrate(first, migrations.toString());
            });
            Future<Map<String, Object>> b = executor.submit(() -> {
                start.await();
                return DatabaseMigrations.migrate(second, migrations.toString());
            });
            Map<String, Object> resultA = a.get(10, TimeUnit.SECONDS);
            Map<String, Object> resultB = b.get(10, TimeUnit.SECONDS);
            assertEquals(1, (Integer) resultA.get("applied") + (Integer) resultB.get("applied"));
            assertEquals(1, (Integer) resultA.get("skipped") + (Integer) resultB.get("skipped"));
            assertEquals(1, scalar(first, "SELECT count(*) AS value FROM concurrent_values"));
            assertEquals(1, scalar(first, "SELECT count(*) AS value FROM _tlang_migrations"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            first.close();
            second.close();
        }
    }

    @Test
    void historyPersistsAcrossCloseAndOneHundredMigrationsRemainOrdered() throws Exception {
        Path migrations = directory("hundred");
        for (int version = 100; version >= 1; version--) {
            write(migrations, "%04d_insert.sql".formatted(version),
                version == 1
                    ? "CREATE TABLE ordered_values(value INTEGER); INSERT INTO ordered_values VALUES (1);"
                    : "INSERT INTO ordered_values VALUES (" + version + ");");
        }
        Path database = temporary.resolve("persistent.sqlite");
        try (DatabaseConnection connection = open(database.toString())) {
            assertEquals(100, DatabaseMigrations.migrate(connection, migrations.toString()).get("applied"));
        }
        try (DatabaseConnection connection = open(database.toString())) {
            assertEquals(100, DatabaseMigrations.migrate(connection, migrations.toString()).get("skipped"));
            assertEquals(100, scalar(connection, "SELECT count(*) AS value FROM ordered_values"));
            assertEquals(5050, scalar(connection, "SELECT sum(value) AS value FROM ordered_values"));
        }
    }

    @Test
    void malformedHistoryClosedConnectionsAndPublicApiAreSafe() throws Exception {
        Path migrations = directory("api");
        write(migrations, "0001_api.sql", "CREATE TABLE api_value(value INTEGER);");
        try (DatabaseConnection connection = open(":memory:")) {
            connection.execute("""
                CREATE TABLE _tlang_migrations(
                    version INTEGER, name TEXT, checksum TEXT, applied_at TEXT)
                """, List.of());
            connection.execute("INSERT INTO _tlang_migrations VALUES (1, 'api', ?, 'not-a-timestamp')",
                List.of("0".repeat(64)));
            assertEquals("Migration history is invalid.", assertThrows(DatabaseFailure.class,
                () -> DatabaseMigrations.status(connection, migrations.toString())).getMessage());
        }

        DatabaseConnection closed = open(":memory:");
        closed.close();
        assertEquals("Connection is closed.", assertThrows(DatabaseFailure.class,
            () -> DatabaseMigrations.migrate(closed, migrations.toString())).getMessage());

        NativeFunction open = (NativeFunction) new DatabaseModule().getExports().get("open");
        Token token = new Token(TokenType.IDENTIFIER, "open", null, 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> handle = (Map<String, Object>) open.call(List.of(":memory:"), token);
        NativeFunction migrate = (NativeFunction) handle.get("migrate");
        NativeFunction status = (NativeFunction) handle.get("migrationStatus");
        assertEquals(1, ((Map<?, ?>) migrate.call(List.of(handle, migrations.toString()), token)).get("applied"));
        assertEquals("applied", ((Map<?, ?>) ((List<?>)
            status.call(List.of(handle, migrations.toString()), token)).getFirst()).get("state"));
        assertThrows(RuntimeError.class, () -> migrate.call(List.of(handle, 123), token));
        ((NativeFunction) handle.get("close")).call(List.of(handle), token);
    }

    @Test
    void emptyDirectoryAndRepeatedRunsRemainUsable() throws Exception {
        Path migrations = directory("empty");
        try (DatabaseConnection connection = open(":memory:")) {
            for (int iteration = 0; iteration < 50; iteration++) {
                assertEquals(Map.of("applied", 0, "skipped", 0),
                    DatabaseMigrations.migrate(connection, migrations.toString()));
            }
            assertTrue(DatabaseMigrations.status(connection, migrations.toString()).isEmpty());
            assertEquals(1, scalar(connection, "SELECT 1 AS value"));
        }
    }

    @Test
    void explicitTransactionControlIsRejectedBeforeAnySqlRuns() throws Exception {
        Path migrations = directory("transaction-control");
        write(migrations, "0001_escape.sql", """
            CREATE TABLE must_not_exist(value INTEGER);
            COMMIT;
            INSERT INTO must_not_exist VALUES (1);
            """);
        try (DatabaseConnection connection = open(":memory:")) {
            DatabaseFailure failure = assertThrows(DatabaseFailure.class,
                () -> DatabaseMigrations.migrate(connection, migrations.toString()));
            assertTrue(failure.getMessage().contains("forbidden transaction control"));
            assertEquals(0, scalar(connection, "SELECT count(*) AS value FROM sqlite_master "
                + "WHERE type='table' AND name='must_not_exist'"));
        }
    }

    private DatabaseConnection open(String target) {
        return new SqliteProvider().open(new DatabaseOptions(target, null, null, 4, 5_000, 5));
    }

    private Path directory(String name) throws Exception {
        return Files.createDirectory(temporary.resolve(name));
    }

    private static Path write(Path directory, String name, String sql) throws Exception {
        return Files.writeString(directory.resolve(name), sql);
    }

    private static List<String> states(DatabaseConnection connection, Path migrations) {
        return DatabaseMigrations.status(connection, migrations.toString()).stream()
            .map(value -> String.valueOf(((Map<?, ?>) value).get("state"))).toList();
    }

    private static int scalar(DatabaseConnection connection, String sql) {
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) connection.query(sql, List.of()).getFirst();
        return (Integer) row.get("value");
    }
}
