package dev.tlang.runtime.database;

import dev.tlang.errors.ErrorFormatter;
import dev.tlang.errors.RuntimeError;
import dev.tlang.errors.RuntimeErrorKind;
import dev.tlang.interpreter.Interpreter;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import dev.tlang.runtime.http.ServerOps;
import dev.tlang.runtime.task.TaskValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static dev.tlang.runtime.database.QueryRuntimeTest.call;
import static dev.tlang.runtime.database.QueryRuntimeTest.compile;
import static dev.tlang.runtime.database.QueryRuntimeTest.global;
import static org.junit.jupiter.api.Assertions.*;

/** Actual TLang source, native errors, tasks, and HTTP exercised on each provider. */
public abstract class RepositoryRuntimeContract {
    private static final Token TOKEN = new Token(TokenType.IDENTIFIER, "repository_test", null, 1, 1);
    protected abstract String connectionExpression();
    private String connectionSource() { return "import db\nlet connection be " + connectionExpression() + "\n"; }

    @Test void languageCrudAndManyTasksShareOneRepository(@TempDir Path directory) {
        Interpreter interpreter = compile(connectionSource() + """
            connection.execute("CREATE TABLE rr_tasks (id INTEGER PRIMARY KEY, name TEXT, hidden TEXT DEFAULT 'private')", [])
            let users be connection.repository("rr_tasks", {primaryKey: "id", fields: ["id", "name"]})
            define write taking id
                users.create({id: id, name: "user-" + id})
                return users.find(id)
            let tasks be []
            repeat 80 times as index
                tasks.add(spawn write(index))
            let results be []
            repeat 80 times as index
                results.add(await tasks.get(index))
            let existsTask be spawn users.exists(40)
            let found be await existsTask
            let base be users.query()
            let active be base.where("id", ">=", 40)
            let page be base.orderBy("id", "desc").limit(1).first()
            let activeCount be active.count()
            let count be users.count()
            let changed be users.update(0, {name: "updated"})
            let removed be users.delete(79)
            let missing be users.find(1000)
            let tx be connection.begin()
            let transactional be tx.repository("rr_tasks", {primaryKey: "id", fields: ["id", "name"]})
            transactional.create({id: 100, name: nil})
            transactional.update(100, {name: "committed"})
            tx.commit()
            let committed be users.find(100)
            connection.close()
            """, directory);
        List<?> results = (List<?>) global(interpreter, "results");
        assertEquals(80, results.size());
        for (int i = 0; i < 80; i++) assertEquals(Map.of("id", i, "name", "user-" + i), results.get(i));
        assertEquals(true, global(interpreter, "found"));
        assertEquals(40, global(interpreter, "activeCount"));
        assertEquals(80, global(interpreter, "count"));
        assertEquals(Map.of("id", 79, "name", "user-79"), global(interpreter, "page"));
        assertEquals(1, global(interpreter, "changed"));
        assertEquals(1, global(interpreter, "removed"));
        assertNull(global(interpreter, "missing"));
        assertEquals(Map.of("id", 100, "name", "committed"), global(interpreter, "committed"));
    }

    @Test void nativeValidationAndConstraintErrorsAbortAndStaySafe(@TempDir Path directory) {
        var interpreter = compile(connectionSource() + """
            connection.execute("CREATE TABLE rr_validation (id INTEGER PRIMARY KEY, name TEXT UNIQUE)", [])
            """, directory);
        Object connection = global(interpreter, "connection");
        var descriptor = Map.of("primaryKey", "id", "fields", List.of("id", "name"));
        try {
            for (Object invalid : Arrays.asList(null, Map.of(), Map.of("primaryKey", "id", "fields", List.of("id--")),
                    Map.of("primaryKey", "missing", "fields", List.of("id")))) {
                Object tx = call(connection, "begin");
                Object repository = call(tx, "repository", "rr_validation", descriptor);
                call(repository, "create", Map.of("id", 1));
                assertSafe(assertThrows(RuntimeError.class, () -> call(tx, "repository", "rr_validation", invalid)));
                assertThrows(RuntimeError.class, () -> call(tx, "commit"));
                assertEquals(0, call(call(connection, "repository", "rr_validation", descriptor), "count"));
            }
            for (String method : List.of("find", "exists", "create", "update", "delete")) {
                Object tx = call(connection, "begin");
                Object repository = call(tx, "repository", "rr_validation", descriptor);
                call(repository, "create", Map.of("id", 1, "name", "secret-server-detail"));
                Object[] args = switch (method) {
                    case "create" -> new Object[]{Map.of("id", 2, "name", "secret-server-detail")};
                    case "update" -> new Object[]{1, Map.of("id", 2)};
                    default -> new Object[]{null};
                };
                assertSafe(assertThrows(RuntimeError.class, () -> call(repository, method, args)));
                assertThrows(RuntimeError.class, () -> call(tx, "commit"));
                assertEquals(0, call(call(connection, "repository", "rr_validation", descriptor), "count"));
            }
            Object tx = call(connection, "begin");
            Object ended = call(tx, "repository", "rr_validation", descriptor);
            Object derived = call(ended, "query");
            call(tx, "rollback");
            assertSafe(assertThrows(RuntimeError.class, () -> call(ended, "find", 1)));
            assertSafe(assertThrows(RuntimeError.class, () -> call(derived, "all")));
        } finally { call(connection, "close"); }
    }

    @Test void taskOwnershipAndFailedTransactionDoNotLeak(@TempDir Path directory) {
        var interpreter = compile(connectionSource() + """
            connection.execute("CREATE TABLE rr_failed_tasks (id INTEGER PRIMARY KEY)", [])
            define makeRepository
                let local be %s
                return local.repository("rr_failed_tasks", {primaryKey: "id", fields: ["id"]})
            let handleTask be spawn makeRepository()
            let escaped be await handleTask
            define failedTransaction
                let tx be connection.begin()
                let local be tx.repository("rr_failed_tasks", {primaryKey: "id", fields: ["id"]})
                local.create({id: 1})
                local.update(1, {id: 2})
            let failed be spawn failedTransaction()
            """.formatted(connectionExpression()), directory);
        try {
            assertSafe(assertThrows(RuntimeError.class, () -> call(global(interpreter, "escaped"), "find", 1)));
            assertSafe(assertThrows(RuntimeError.class, () -> interpreter.getTaskRuntime().await(
                (TaskValue) global(interpreter, "failed"), TOKEN)));
            Object connection = global(interpreter, "connection");
            assertEquals(0, call(call(connection, "repository", "rr_failed_tasks",
                Map.of("primaryKey", "id", "fields", List.of("id"))), "count"));
        } finally { call(global(interpreter, "connection"), "close"); }
    }

    @Test void concurrentHttpReadsWritesFailuresAndRequestOwnership(@TempDir Path directory) throws Exception {
        var interpreter = compile(connectionSource() + """
            connection.execute("CREATE TABLE rr_http (id TEXT PRIMARY KEY, value TEXT, password_hash TEXT DEFAULT 'private')", [])
            let users be connection.repository("rr_http", {primaryKey: "id", fields: ["id", "value"]})
            let escaped be []
            define create taking req and res
                users.create({id: req.body, value: req.body})
                res.text(users.find(req.body).value)
            define read taking req and res
                res.json(users.find(req.params.id))
            define validationFailure taking req and res
                users.update("request-0", {password_hash: "bad"})
            define databaseFailure taking req and res
                users.create({id: "request-0", value: "duplicate"})
            define owned taking req and res
                let local be %s
                local.execute("CREATE TABLE IF NOT EXISTS rr_owned (id INTEGER PRIMARY KEY)", [])
                let repository be local.repository("rr_owned", {primaryKey: "id", fields: ["id"]})
                escaped.add(repository)
                res.text("owned")
            define transactionFailure taking req and res
                let tx be connection.begin()
                let repository be tx.repository("rr_http", {primaryKey: "id", fields: ["id", "value"]})
                repository.create({id: "rolled-back", value: "discarded"})
                repository.update("rolled-back", {id: "changed"})
            """.formatted(connectionExpression()), directory);
        var server = new ServerOps(0, 8, 128);
        server.addRoute("POST", "/users", global(interpreter, "create"), TOKEN);
        server.addRoute("GET", "/users/:id", global(interpreter, "read"), TOKEN);
        for (String name : List.of("validationFailure", "databaseFailure", "owned", "transactionFailure")) {
            server.addRoute("GET", "/" + name, global(interpreter, name), TOKEN);
        }
        server.start(interpreter, TOKEN);
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try {
            var requests = new ArrayList<CompletableFuture<HttpResponse<String>>>();
            for (int i = 0; i < 60; i++) {
                var request = request(server, "/users").POST(HttpRequest.BodyPublishers.ofString("request-" + i)).build();
                requests.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
            }
            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
            for (int i = 0; i < requests.size(); i++) {
                assertEquals(200, requests.get(i).join().statusCode());
                assertEquals("request-" + i, requests.get(i).join().body());
            }
            var read = client.send(request(server, "/users/request-0").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, read.statusCode());
            assertTrue(read.body().contains("request-0"));
            assertFalse(read.body().contains("password_hash"));
            for (String path : List.of("validationFailure", "databaseFailure", "transactionFailure")) {
                var response = client.send(request(server, "/" + path).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(500, response.statusCode());
                assertEquals("Internal Server Error", response.body());
            }
            var owned = client.send(request(server, "/owned").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, owned.statusCode());
            assertEquals(60, call(global(interpreter, "users"), "count"));
            assertEquals(false, call(global(interpreter, "users"), "exists", "rolled-back"));
        } finally {
            server.stop();
            call(global(interpreter, "connection"), "close");
        }
        assertSafe(assertThrows(RuntimeError.class, () -> call(
            ((List<?>) global(interpreter, "escaped")).getFirst(), "find", 1)));
        assertSafe(assertThrows(RuntimeError.class, () -> call(global(interpreter, "users"), "count")));
    }

    private static HttpRequest.Builder request(ServerOps server, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getBoundPort() + path))
            .timeout(Duration.ofSeconds(10));
    }

    private static void assertSafe(RuntimeError failure) {
        assertEquals(RuntimeErrorKind.DATABASE_ERROR, failure.getKind());
        assertNull(failure.getCause());
        String formatted = ErrorFormatter.format(failure);
        for (String text : List.of("secret-server-detail", "org.postgresql", "23505", "postgresql://", "password_hash")) {
            assertFalse(formatted.contains(text));
        }
    }
}
