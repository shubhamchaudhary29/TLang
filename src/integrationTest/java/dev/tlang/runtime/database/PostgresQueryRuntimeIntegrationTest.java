package dev.tlang.runtime.database;

import dev.tlang.ast.Stmt;
import dev.tlang.errors.SemanticError;
import dev.tlang.errors.RuntimeError;
import dev.tlang.interpreter.Environment;
import dev.tlang.interpreter.Interpreter;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import dev.tlang.modules.ModuleLoader;
import dev.tlang.parser.Parser;
import dev.tlang.resolver.Resolver;
import dev.tlang.runtime.http.ServerOps;
import dev.tlang.runtime.task.TaskValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
final class PostgresQueryRuntimeIntegrationTest {
    private static final Token TOKEN = new Token(TokenType.IDENTIFIER, "test", null, 1, 1);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine")
        .withDatabaseName("tlang_runtime")
        .withUsername("tlang")
        .withPassword("runtime-test-password");

    @Test
    void spawnedTasksSharePostgresHandleWithoutSerializingThePool(@TempDir Path directory) {
        Interpreter interpreter = compile(connectionSource() + """
            connection.execute("CREATE TABLE task_values (id INTEGER PRIMARY KEY, value TEXT NOT NULL)", [])

            define insertValue taking id
                return connection.table("task_values").insert({id: id, value: "value-" + id})

            let tasks be []
            repeat 100 times as index
                tasks.add(spawn insertValue(index))

            let completed be 0
            repeat 100 times as index
                let affected be await tasks.get(index)
                set completed to completed + affected

            let finalCount be connection.table("task_values").count()
            """, directory);

        assertEquals(100, global(interpreter, "completed"));
        assertEquals(100, global(interpreter, "finalCount"));
        closeGlobalConnection(interpreter);
    }

    @Test
    void failedTaskAutomaticallyRollsBackItsPinnedTransaction(@TempDir Path directory) {
        Interpreter interpreter = compile(connectionSource() + """
            connection.execute("CREATE TABLE failed_task_values (id INTEGER PRIMARY KEY)", [])

            define failAfterBorrow
                let localConnection be db.open("%s", {poolSize: 2, connectionTimeoutMs: 5000, queryTimeoutSeconds: 5})
                let transaction be localConnection.begin()
                transaction.table("failed_task_values").insert({id: 1})
                let broken be 1 / 0

            let failedTask be spawn failAfterBorrow()
            """.formatted(escape(authenticatedTarget())), directory);

        TaskValue task = (TaskValue) global(interpreter, "failedTask");
        assertThrows(RuntimeError.class,
            () -> interpreter.getTaskRuntime().await(task, TOKEN));

        @SuppressWarnings("unchecked")
        Map<String, Object> connection = (Map<String, Object>) global(interpreter, "connection");
        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) ((NativeFunction) connection.get("query")).call(
            List.of(connection, "SELECT count(*) AS value FROM failed_task_values", List.of()), TOKEN);
        @SuppressWarnings("unchecked")
        Map<String, Object> count = (Map<String, Object>) rows.getFirst();
        assertEquals(0, count.get("value"));
        closeGlobalConnection(interpreter);
    }

    @Test
    void concurrentHttpRequestsUsePostgresAndFailuresStayPrivate(@TempDir Path directory) throws Exception {
        Interpreter interpreter = compile(connectionSource() + """
            connection.execute("CREATE TABLE http_values (id SERIAL PRIMARY KEY, value TEXT UNIQUE NOT NULL)", [])
            connection.execute("CREATE TABLE failed_http_values (id INTEGER PRIMARY KEY)", [])

            define writeValue taking req and res
                connection.table("http_values").insert({value: req.body})
                let row be connection.table("http_values").where("value", "=", req.body).first()
                res.text(row.value)

            define databaseFailure taking req and res
                connection.table("secret_internal_table").all()
                res.text("unreachable")

            define runtimeFailureWithTransaction taking req and res
                let transaction be connection.begin()
                transaction.table("failed_http_values").insert({id: 1})
                let broken be 1 / 0
                res.text("unreachable")
            """, directory);

        ServerOps server = new ServerOps(0, 12, 256);
        server.addRoute("POST", "/values", global(interpreter, "writeValue"), TOKEN);
        server.addRoute("GET", "/failure", global(interpreter, "databaseFailure"), TOKEN);
        server.addRoute("GET", "/transaction-failure", global(interpreter, "runtimeFailureWithTransaction"), TOKEN);
        server.start(interpreter, TOKEN);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try {
            List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                String body = "request-" + index;
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.getBoundPort() + "/values"))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
                requests.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
            }
            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                .get(20, TimeUnit.SECONDS);
            for (int index = 0; index < requests.size(); index++) {
                HttpResponse<String> response = requests.get(index).join();
                assertEquals(200, response.statusCode(), response.body());
                assertEquals("request-" + index, response.body());
            }

            HttpRequest failureRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.getBoundPort() + "/failure"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            HttpResponse<String> failure = client.send(
                failureRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(500, failure.statusCode());
            assertEquals("Internal Server Error", failure.body());
            assertFalse(failure.body().contains("secret_internal_table"));
            assertFalse(failure.body().contains("PostgreSQL"));
            assertFalse(failure.body().contains("org.postgresql"));

            HttpRequest transactionFailureRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.getBoundPort() + "/transaction-failure"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            HttpResponse<String> transactionFailure = client.send(
                transactionFailureRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(500, transactionFailure.statusCode());
            assertEquals("Internal Server Error", transactionFailure.body());
        } finally {
            server.stop();
            closeGlobalConnection(interpreter);
        }

        DatabaseConnection verifier = new PostgresProvider().open(options());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) verifier.query(
                "SELECT count(*) AS value FROM http_values", List.of()).getFirst();
            assertEquals(100, row.get("value"));
            @SuppressWarnings("unchecked")
            Map<String, Object> failedRows = (Map<String, Object>) verifier.query(
                "SELECT count(*) AS value FROM failed_http_values", List.of()).getFirst();
            assertEquals(0, failedRows.get("value"));
        } finally {
            verifier.close();
        }
    }

    private static String connectionSource() {
        return """
            import db
            let connection be db.open("%s", {
                username: "%s",
                password: "%s",
                poolSize: 12,
                connectionTimeoutMs: 5000,
                queryTimeoutSeconds: 5
            })
            """.formatted(escape(target()), escape(POSTGRES.getUsername()), escape(POSTGRES.getPassword()));
    }

    private static Interpreter compile(String source, Path directory) {
        List<Token> tokens = new Lexer(
            source, directory.resolve("postgres-runtime-test.tiny").toString()).tokenize();
        List<Stmt> statements = new Parser(tokens).parse();
        List<SemanticError> errors = new Resolver().resolve(statements);
        assertTrue(errors.isEmpty(), () -> "semantic errors: " + errors);
        Interpreter interpreter = new Interpreter(new ModuleLoader(directory));
        interpreter.interpret(statements);
        return interpreter;
    }

    private static Object global(Interpreter interpreter, String name) {
        Environment globals = interpreter.getGlobalEnvironment();
        return globals.get(new Token(TokenType.IDENTIFIER, name, null, 1, 1));
    }

    @SuppressWarnings("unchecked")
    private static void closeGlobalConnection(Interpreter interpreter) {
        Map<String, Object> connection = (Map<String, Object>) global(interpreter, "connection");
        ((NativeFunction) connection.get("close")).call(List.of(connection), TOKEN);
    }

    private static DatabaseOptions options() {
        return new DatabaseOptions(target(), POSTGRES.getUsername(), POSTGRES.getPassword(), 4, 5_000, 5);
    }

    private static String target() {
        return "postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
            + "/" + POSTGRES.getDatabaseName();
    }

    private static String authenticatedTarget() {
        return "postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
            + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
            + "/" + POSTGRES.getDatabaseName();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
