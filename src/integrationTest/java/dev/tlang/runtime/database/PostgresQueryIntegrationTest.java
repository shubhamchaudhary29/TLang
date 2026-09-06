package dev.tlang.runtime.database;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
final class PostgresQueryIntegrationTest extends QueryContract {
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine")
        .withDatabaseName("query_builder").withUsername("tlang").withPassword("query-test-password");

    @org.junit.jupiter.api.Test void brokenPinnedSessionAndExhaustedPoolRecover() {
        DatabaseConnection connection = open();
        try {
            connection.execute("CREATE TABLE qb_recovery (id INTEGER PRIMARY KEY)", java.util.List.of());
            var query = DatabaseQuery.table("qb_recovery");
            var victim = connection.begin();
            int pid = (Integer) ((java.util.Map<?, ?>) victim.query("SELECT pg_backend_pid() AS pid", java.util.List.of()).getFirst()).get("pid");
            connection.query("SELECT pg_terminate_backend(?) AS terminated", java.util.List.of(pid));
            org.junit.jupiter.api.Assertions.assertThrows(DatabaseFailure.class, () -> query.all(victim));
            org.junit.jupiter.api.Assertions.assertTrue(victim.isClosed());
            org.junit.jupiter.api.Assertions.assertEquals(1, query.insert(connection, java.util.Map.of("id", 1)));
            var pinned = new java.util.ArrayList<DatabaseTransaction>();
            try {
                for (int i = 0; i < 4; i++) pinned.add(connection.begin());
                org.junit.jupiter.api.Assertions.assertThrows(DatabaseFailure.class, () -> query.count(connection));
            } finally { pinned.forEach(DatabaseTransaction::abort); }
            org.junit.jupiter.api.Assertions.assertEquals(1, query.count(connection));
        } finally { connection.close(); }
    }

    @org.junit.jupiter.api.Test void runtimeErrorsDoNotExposeCredentialsOrServerDetails() {
        var token = new dev.tlang.lexer.Token(dev.tlang.lexer.TokenType.IDENTIFIER, "query", null, 1, 1);
        var module = new dev.tlang.modules.DatabaseModule();
        Object handle = ((dev.tlang.interpreter.NativeFunction) module.getExports().get("open")).call(
            java.util.List.of("postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName(),
                java.util.Map.of("username", POSTGRES.getUsername(), "password", POSTGRES.getPassword())), token);
        try {
            QueryRuntimeTest.call(handle, "execute", "CREATE TABLE qb_redaction (value TEXT UNIQUE)", java.util.List.of());
            Object query = QueryRuntimeTest.call(handle, "table", "qb_redaction");
            QueryRuntimeTest.call(query, "insert", java.util.Map.of("value", "secret-server-detail"));
            var failure = org.junit.jupiter.api.Assertions.assertThrows(dev.tlang.errors.RuntimeError.class,
                () -> QueryRuntimeTest.call(query, "insert", java.util.Map.of("value", "secret-server-detail")));
            String formatted = dev.tlang.errors.ErrorFormatter.format(failure);
            for (String secret : java.util.List.of("secret-server-detail", POSTGRES.getPassword(), "org.postgresql", "23505", "postgresql://")) {
                org.junit.jupiter.api.Assertions.assertFalse(formatted.contains(secret));
            }
            org.junit.jupiter.api.Assertions.assertNull(failure.getCause());
            org.junit.jupiter.api.Assertions.assertEquals(1, QueryRuntimeTest.call(query, "count"));
        } finally { QueryRuntimeTest.call(handle, "close"); }
    }

    @Override protected DatabaseConnection open() {
        return new PostgresProvider().open(new DatabaseOptions(
            "postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName(),
            POSTGRES.getUsername(), POSTGRES.getPassword(), 4, 5000, 5));
    }
}
