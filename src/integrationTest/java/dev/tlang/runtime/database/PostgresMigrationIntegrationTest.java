package dev.tlang.runtime.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
final class PostgresMigrationIntegrationTest {
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine")
        .withDatabaseName("tlang")
        .withUsername("m2_user_secret_7fd91")
        .withPassword("tlang-migration-test-password");

    @TempDir Path temporary;

    @Test
    void freshDatabaseMultipleMigrationsRerunHistoryAndStatus() throws Exception {
        String database = createDatabase();
        Path migrations = directory("basic");
        write(migrations, "0002_seed.sql", """
            INSERT INTO migrated_users(name) VALUES ('first');
            INSERT INTO migrated_users(name) VALUES ('unicode नमस्ते 🌍');
            """);
        write(migrations, "0001_create_users.sql", """
            CREATE TABLE migrated_users(id SERIAL PRIMARY KEY, name TEXT NOT NULL);
            """);

        try (DatabaseConnection connection = open(database, 3, 5)) {
            assertEquals(List.of("pending", "pending"), states(connection, migrations));
            assertEquals(Map.of("applied", 2, "skipped", 0),
                DatabaseMigrations.migrate(connection, migrations.toString()));
            assertEquals(Map.of("applied", 0, "skipped", 2),
                DatabaseMigrations.migrate(connection, migrations.toString()));
            assertEquals(List.of("applied", "applied"), states(connection, migrations));
            assertEquals(2, scalar(connection, "SELECT count(*) AS value FROM migrated_users"));
            assertEquals(2, scalar(connection, "SELECT count(*) AS value FROM _tlang_migrations"));
            assertEquals(2, scalar(connection, "SELECT count(*) AS value FROM _tlang_migrations "
                + "WHERE applied_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T'"));
            assertEquals(1, scalar(connection, "SELECT 1 AS value"));
        }
    }

    @Test
    void checksumAndOutOfOrderProtectionDoNotMutateDatabase() throws Exception {
        String database = createDatabase();
        Path migrations = directory("integrity");
        Path first = write(migrations, "0001_one.sql",
            "CREATE TABLE integrity_values(value INTEGER); INSERT INTO integrity_values VALUES (1);");
        write(migrations, "0003_three.sql", "INSERT INTO integrity_values VALUES (3);");
        try (DatabaseConnection connection = open(database, 2, 5)) {
            DatabaseMigrations.migrate(connection, migrations.toString());
            Files.writeString(first,
                "CREATE TABLE changed_table(value INTEGER); INSERT INTO integrity_values VALUES (99);");
            DatabaseFailure changed = assertThrows(DatabaseFailure.class,
                () -> DatabaseMigrations.migrate(connection, migrations.toString()));
            assertTrue(changed.getMessage().contains("changed after being applied"));
            assertEquals(2, scalar(connection, "SELECT count(*) AS value FROM integrity_values"));
            assertEquals(0, scalar(connection, "SELECT count(*) AS value FROM information_schema.tables "
                + "WHERE table_schema='public' AND table_name='changed_table'"));

            Files.writeString(first,
                "CREATE TABLE integrity_values(value INTEGER); INSERT INTO integrity_values VALUES (1);");
            write(migrations, "0002_two.sql", "INSERT INTO integrity_values VALUES (2);");
            DatabaseFailure order = assertThrows(DatabaseFailure.class,
                () -> DatabaseMigrations.migrate(connection, migrations.toString()));
            assertTrue(order.getMessage().contains("out of order"));
            assertEquals(0, scalar(connection,
                "SELECT count(*) AS value FROM integrity_values WHERE value=2"));
        }
    }

    @Test
    void failedMigrationRollsBackAndCorrectedMigrationCanRun() throws Exception {
        String database = createDatabase();
        Path migrations = directory("rollback");
        Path migration = write(migrations, "0001_atomic.sql", """
            CREATE TABLE atomic_values(value INTEGER UNIQUE);
            INSERT INTO atomic_values VALUES (1);
            INSERT INTO atomic_values VALUES (1);
            """);
        try (DatabaseConnection connection = open(database, 1, 5)) {
            DatabaseFailure failure = assertThrows(DatabaseFailure.class,
                () -> DatabaseMigrations.migrate(connection, migrations.toString()));
            assertTrue(failure.getMessage().contains("transaction rolled back"));
            assertEquals(0, scalar(connection, "SELECT count(*) AS value FROM information_schema.tables "
                + "WHERE table_schema='public' AND table_name='atomic_values'"));
            assertEquals(0, scalar(connection,
                "SELECT count(*) AS value FROM _tlang_migrations"));
            assertEquals(1, scalar(connection, "SELECT 1 AS value"));

            Files.writeString(migration, """
                CREATE TABLE atomic_values(value INTEGER UNIQUE);
                INSERT INTO atomic_values VALUES (1);
                INSERT INTO atomic_values VALUES (2);
                """);
            assertEquals(1, DatabaseMigrations.migrate(connection, migrations.toString()).get("applied"));
            assertEquals(2, scalar(connection, "SELECT count(*) AS value FROM atomic_values"));
            assertEquals(1, scalar(connection, "SELECT count(*) AS value FROM _tlang_migrations"));
        }
    }

    @Test
    void multilineCommentsHostileTextAndDollarQuotedFunctionRemainIntact() throws Exception {
        String database = createDatabase();
        Path migrations = directory("scripts");
        write(migrations, "0001_scripts.sql", """
            -- a comment containing ; and SQL-looking DROP TABLE payloads;
            CREATE TABLE payloads(value TEXT);
            /* multiline ; comment
               INSERT INTO payloads VALUES ('wrong'); */
            INSERT INTO payloads(value) VALUES ('hello; world');
            INSERT INTO payloads(value) VALUES (''' OR true; DROP TABLE payloads; --');

            CREATE FUNCTION migration_answer() RETURNS integer AS $function$
            BEGIN
              PERFORM 'function; body';
              RETURN 42;
            END;
            $function$ LANGUAGE plpgsql;
            """);
        try (DatabaseConnection connection = open(database, 2, 5)) {
            DatabaseMigrations.migrate(connection, migrations.toString());
            assertEquals(2, scalar(connection, "SELECT count(*) AS value FROM payloads"));
            assertEquals(42, scalar(connection, "SELECT migration_answer() AS value"));
            assertEquals(1, scalar(connection, "SELECT count(*) AS value FROM pg_proc "
                + "WHERE proname='migration_answer'"));
        }
    }

    @Test
    void concurrentMigratorsSerializeWithoutDoubleApplication() throws Exception {
        String database = createDatabase();
        Path migrations = directory("concurrent");
        write(migrations, "0001_deploy_once.sql", """
            CREATE TABLE deploy_once(value INTEGER);
            SELECT pg_sleep(0.35);
            INSERT INTO deploy_once VALUES (1);
            """);
        DatabaseConnection first = open(database, 2, 5);
        DatabaseConnection second = open(database, 2, 5);
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
            Map<String, Object> resultA = a.get(15, TimeUnit.SECONDS);
            Map<String, Object> resultB = b.get(15, TimeUnit.SECONDS);
            assertEquals(1, (Integer) resultA.get("applied") + (Integer) resultB.get("applied"));
            assertEquals(1, (Integer) resultA.get("skipped") + (Integer) resultB.get("skipped"));
            assertEquals(1, scalar(first, "SELECT count(*) AS value FROM deploy_once"));
            assertEquals(1, scalar(first, "SELECT count(*) AS value FROM _tlang_migrations"));
            assertEquals(1, scalar(second, "SELECT 1 AS value"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            first.close();
            second.close();
        }
    }

    @Test
    void lockAndPoolRecoverAfterConcurrentFailuresAndCredentialsStayRedacted() throws Exception {
        String database = createDatabase();
        Path migrations = directory("lock-recovery");
        Path migration = write(migrations, "0001_failure.sql", """
            SELECT pg_sleep(0.25);
            CREATE TABLE failed_table(value INTEGER);
            SELECT FROM;
            """);
        DatabaseConnection first = open(database, 1, 3);
        DatabaseConnection second = open(database, 1, 3);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<DatabaseFailure> a = executor.submit(() -> failureFrom(first, migrations));
            Future<DatabaseFailure> b = executor.submit(() -> failureFrom(second, migrations));
            DatabaseFailure firstFailure = a.get(15, TimeUnit.SECONDS);
            DatabaseFailure secondFailure = b.get(15, TimeUnit.SECONDS);
            assertSafe(firstFailure);
            assertSafe(secondFailure);
            assertEquals(0, scalar(first, "SELECT count(*) AS value FROM information_schema.tables "
                + "WHERE table_schema='public' AND table_name='failed_table'"));
            assertEquals(0, scalar(second,
                "SELECT count(*) AS value FROM _tlang_migrations"));

            Files.writeString(migration, """
                CREATE TABLE recovered_table(value INTEGER);
                INSERT INTO recovered_table VALUES (1);
                """);
            assertEquals(1, DatabaseMigrations.migrate(second, migrations.toString()).get("applied"));
            assertEquals(1, scalar(first, "SELECT count(*) AS value FROM recovered_table"));
            assertEquals(1, scalar(second, "SELECT 1 AS value"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            first.close();
            second.close();
        }
    }

    @Test
    void advisoryLockWaitIsBoundedAndRepeatedEmptyRunsDoNotLeakPoolConnections() throws Exception {
        String database = createDatabase();
        Path migrations = directory("bounded-lock");
        String jdbcUrl = POSTGRES.getJdbcUrl().replace(
            "/" + POSTGRES.getDatabaseName(), "/" + database);
        try (DatabaseConnection connection = open(database, 1, 1);
             var lockOwner = DriverManager.getConnection(
                 jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             var lock = lockOwner.createStatement()) {
            lock.execute("SELECT pg_advisory_lock(" +
                "hashtext(current_database()), hashtext('_tlang_migrations'))");
            long started = System.nanoTime();
            DatabaseFailure failure = assertTimeoutPreemptively(Duration.ofSeconds(4),
                () -> assertThrows(DatabaseFailure.class,
                    () -> DatabaseMigrations.migrate(connection, migrations.toString())));
            assertTrue(failure.getMessage().contains("Migration lock could not be acquired"));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 750);
            lock.execute("SELECT pg_advisory_unlock(" +
                "hashtext(current_database()), hashtext('_tlang_migrations'))");

            for (int iteration = 0; iteration < 25; iteration++) {
                assertEquals(Map.of("applied", 0, "skipped", 0),
                    DatabaseMigrations.migrate(connection, migrations.toString()));
            }
            assertEquals(1, scalar(connection, "SELECT 1 AS value"));
        }
    }

    private String createDatabase() throws Exception {
        String name = "tlang_m2_" + DATABASE_SEQUENCE.incrementAndGet();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return name;
    }

    private DatabaseConnection open(String database, int poolSize, int timeoutSeconds) {
        String target = "postgresql://" + POSTGRES.getHost() + ":"
            + POSTGRES.getMappedPort(5432) + "/" + database;
        return new PostgresProvider().open(new DatabaseOptions(
            target, POSTGRES.getUsername(), POSTGRES.getPassword(),
            poolSize, 5_000, timeoutSeconds));
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

    private static DatabaseFailure failureFrom(DatabaseConnection connection, Path migrations) {
        return assertThrows(DatabaseFailure.class,
            () -> DatabaseMigrations.migrate(connection, migrations.toString()));
    }

    private static void assertSafe(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String rendered = current + " " + current.getMessage();
            assertFalse(rendered.contains(POSTGRES.getPassword()));
            assertFalse(rendered.contains(POSTGRES.getUsername()));
            assertFalse(rendered.contains(POSTGRES.getJdbcUrl()));
            current = current.getCause();
        }
    }
}
