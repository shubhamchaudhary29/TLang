package dev.tlang.runtime.http;

import dev.tlang.ast.Stmt;
import dev.tlang.errors.RuntimeError;
import dev.tlang.errors.SemanticError;
import dev.tlang.interpreter.Environment;
import dev.tlang.interpreter.Interpreter;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.interpreter.RuntimeCollections;
import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import dev.tlang.modules.ModuleLoader;
import dev.tlang.parser.Parser;
import dev.tlang.resolver.Resolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentHttpServerTest {
    private static final Token TOKEN = new Token(TokenType.IDENTIFIER, "test", null, 1, 1);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT)
        .build();
    private final List<ServerOps> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        for (ServerOps server : servers) {
            server.stop();
        }
    }

    @Test
    void handlersActuallyExecuteInParallel() throws Exception {
        CyclicBarrier bothHandlersEntered = new CyclicBarrier(2);
        Set<String> workerNames = ConcurrentHashMap.newKeySet();
        NativeFunction handler = handler((args, token) -> {
            workerNames.add(Thread.currentThread().getName());
            try {
                bothHandlersEntered.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeError(token, "parallel barrier failed: " + e.getMessage());
            }
            respond(args, "parallel");
        });

        ServerOps server = new ServerOps(0, 2, 32);
        servers.add(server);
        server.addRoute("GET", "/parallel", handler, TOKEN);
        server.start(new Interpreter(), TOKEN);

        List<HttpResponse<String>> responses = await(List.of(
            send(server, "GET", "/parallel", "", Map.of()),
            send(server, "GET", "/parallel", "", Map.of())
        ));

        assertEquals(List.of(200, 200), responses.stream().map(HttpResponse::statusCode).toList());
        assertEquals(Set.of("parallel"), Set.copyOf(responses.stream().map(HttpResponse::body).toList()));
        assertEquals(2, workerNames.size(), "both fixed-pool workers must enter the handler concurrently");
    }

    @Test
    void requestLocalsClosuresAndResponsesStayIsolatedUnderStress() throws Exception {
        Interpreter interpreter = compile("""
            let prefix be "hello"

            define descend taking n
                if n == 0
                    return prefix
                return descend(n - 1)

            define isEven taking n
                if n == 0
                    return true
                return isOdd(n - 1)

            define isOdd taking n
                if n == 0
                    return false
                return isEven(n - 1)

            define makeGreeting taking suffix
                define captured taking value and marker be "default"
                    let local be value
                    if isEven(4)
                        return descend(3) + ":" + suffix + ":" + marker + ":" + local
                    return "unreachable"
                return captured

            let greet be makeGreeting("closure")

            define handle taking req and res
                let identity be req.headers.get("authorization")
                let value be req.params.id + ":" + req.query.q + ":" + req.body + ":" + identity
                res.header("x-request-id", req.params.id).text(greet(value))
            """, Path.of("."));

        ServerOps server = new ServerOps(0, 8, 128);
        servers.add(server);
        server.addRoute("POST", "/echo/:id", global(interpreter, "handle"), TOKEN);
        server.start(interpreter, TOKEN);

        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String id = "request-" + i;
            requests.add(send(server, "POST", "/echo/" + id + "?q=query-" + i,
                "body-" + i, Map.of("Authorization", "secret-" + i)));
        }
        List<HttpResponse<String>> responses = await(requests);

        for (int i = 0; i < responses.size(); i++) {
            HttpResponse<String> response = responses.get(i);
            assertEquals(200, response.statusCode());
            assertEquals("request-" + i, response.headers().firstValue("x-request-id").orElseThrow());
            assertEquals("hello:closure:default:request-" + i + ":query-" + i + ":body-" + i + ":secret-" + i,
                response.body());
        }
    }

    @Test
    void oneRuntimeFailureDoesNotAffectConcurrentSuccesses() throws Exception {
        Interpreter interpreter = compile("""
            define fail taking req and res
                let broken be 1 / 0
                res.text("unreachable")

            define succeed taking req and res
                res.text(req.body)
            """, Path.of("."));
        ServerOps server = new ServerOps(0, 4, 32);
        servers.add(server);
        server.addRoute("POST", "/fail", global(interpreter, "fail"), TOKEN);
        server.addRoute("POST", "/ok", global(interpreter, "succeed"), TOKEN);
        server.start(interpreter, TOKEN);

        List<HttpResponse<String>> responses = await(List.of(
            send(server, "POST", "/fail", "bad", Map.of()),
            send(server, "POST", "/ok", "A", Map.of()),
            send(server, "POST", "/ok", "B", Map.of())
        ));

        assertEquals(500, responses.get(0).statusCode());
        assertTrue(responses.get(0).body().contains("Division by zero"));
        assertEquals("A", responses.get(1).body());
        assertEquals("B", responses.get(2).body());
        assertTrue(server.isRunning());
    }

    @Test
    void nativeJsonAndValidationModulesAreConcurrent() throws Exception {
        Interpreter interpreter = compile("""
            import json
            import validate

            define handle taking req and res
                let payload be json.parse(req.body)
                let result be validate.check(payload, {"id": {"required": true, "type": "string"}})
                if result.valid
                    res.text(payload.id)
                otherwise
                    res.status(400).json(result.errors)
            """, Path.of("."));
        ServerOps server = new ServerOps(0, 8, 128);
        servers.add(server);
        server.addRoute("POST", "/module", global(interpreter, "handle"), TOKEN);
        server.start(interpreter, TOKEN);

        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            requests.add(send(server, "POST", "/module", "{\"id\":\"value-" + i + "\"}",
                Map.of("Content-Type", "application/json")));
        }
        List<HttpResponse<String>> responses = await(requests);
        for (int i = 0; i < responses.size(); i++) {
            assertEquals(200, responses.get(i).statusCode());
            assertEquals("value-" + i, responses.get(i).body());
        }
    }

    @Test
    void sharedMutableListsAndMapsRemainStructurallySafe() throws Exception {
        Interpreter interpreter = compile("""
            let sharedList be []
            let sharedMap be {}

            define handle taking req and res
                sharedList.add(req.body)
                sharedMap.put(req.body, req.body)
                res.text(req.body)
            """, Path.of("."));
        ServerOps server = new ServerOps(0, 8, 256);
        servers.add(server);
        server.addRoute("POST", "/mutate", global(interpreter, "handle"), TOKEN);
        server.start(interpreter, TOKEN);

        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            requests.add(send(server, "POST", "/mutate", "key-" + i, Map.of()));
        }
        List<HttpResponse<String>> responses = await(requests);
        for (int i = 0; i < responses.size(); i++) {
            assertEquals(200, responses.get(i).statusCode());
            assertEquals("key-" + i, responses.get(i).body());
        }

        @SuppressWarnings("unchecked")
        List<Object> sharedList = (List<Object>) global(interpreter, "sharedList");
        @SuppressWarnings("unchecked")
        Map<String, Object> sharedMap = (Map<String, Object>) global(interpreter, "sharedMap");
        assertEquals(200, RuntimeCollections.snapshot(sharedList).size());
        assertEquals(200, RuntimeCollections.snapshot(sharedMap).size());
    }

    @Test
    void sharedSqliteConnectionSerializesConcurrentReadsAndWrites(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("concurrent.sqlite");
        String path = database.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        Interpreter interpreter = compile("""
            import db
            let connection be db.open("%s")
            connection.execute("CREATE TABLE messages (value TEXT NOT NULL)", [])

            define write taking req and res
                connection.insert("INSERT INTO messages(value) VALUES (?)", [req.body])
                let rows be connection.query("SELECT value FROM messages WHERE value = ?", [req.body])
                res.text(rows.get(0).value)
            """.formatted(path), tempDir);
        ServerOps server = new ServerOps(0, 8, 128);
        servers.add(server);
        server.addRoute("POST", "/db", global(interpreter, "write"), TOKEN);
        server.start(interpreter, TOKEN);

        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            requests.add(send(server, "POST", "/db", "row-" + i, Map.of()));
        }
        List<HttpResponse<String>> responses = await(requests);
        for (int i = 0; i < responses.size(); i++) {
            assertEquals(200, responses.get(i).statusCode(), responses.get(i).body());
            assertEquals("row-" + i, responses.get(i).body());
        }

        try (Connection check = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = check.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM messages")) {
            assertTrue(result.next());
            assertEquals(40, result.getInt(1));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> connection = (Map<String, Object>) global(interpreter, "connection");
        ((NativeFunction) connection.get("close")).call(List.of(connection), TOKEN);
    }

    @Test
    void routingMethodsAndErrorResponsesRemainIndependent() throws Exception {
        NativeFunction echo = handler((args, token) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = (Map<String, Object>) args.get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) request.get("params");
            respond(args, request.get("method") + ":" + params.get("id") + ":" + request.get("body"));
        });
        ServerOps server = new ServerOps(0, 6, 64);
        servers.add(server);
        server.addRoute("GET", "/items/:id", echo, TOKEN);
        server.addRoute("POST", "/items/:id", echo, TOKEN);
        server.addRoute("DELETE", "/items/:id", echo, TOKEN);
        server.start(new Interpreter(), TOKEN);

        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            String method = switch (i % 3) {
                case 0 -> "GET";
                case 1 -> "POST";
                default -> "DELETE";
            };
            requests.add(send(server, method, "/items/" + i, "body-" + i, Map.of()));
        }
        List<HttpResponse<String>> responses = await(requests);
        for (int i = 0; i < responses.size(); i++) {
            String method = switch (i % 3) {
                case 0 -> "GET";
                case 1 -> "POST";
                default -> "DELETE";
            };
            assertEquals(method + ":" + i + ":body-" + i, responses.get(i).body());
        }

        assertEquals(404, send(server, "GET", "/missing", "", Map.of()).get().statusCode());
        HttpResponse<String> invalidMethod = send(server, "PUT", "/items/1", "", Map.of()).get();
        assertEquals(405, invalidMethod.statusCode());
        assertTrue(invalidMethod.headers().firstValue("allow").orElseThrow().contains("GET"));
    }

    @Test
    void stopTerminatesWorkersAndServerCannotRestartOrMutateRoutes() throws Exception {
        ServerOps server = new ServerOps(0, 2, 8);
        servers.add(server);
        server.addRoute("GET", "/", handler((args, token) -> respond(args, "ok")), TOKEN);
        server.start(new Interpreter(), TOKEN);
        assertEquals("ok", send(server, "GET", "/", "", Map.of()).get().body());

        assertThrows(RuntimeError.class,
            () -> server.addRoute("GET", "/late", handler((args, token) -> respond(args, "late")), TOKEN));
        server.stop();

        assertFalse(server.isRunning());
        assertThrows(RuntimeError.class, () -> server.start(new Interpreter(), TOKEN));
        assertEventuallyNoHttpWorkers();
    }

    @Test
    void shutdownAllowsAnActiveRequestToFinish() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        NativeFunction handler = handler((args, token) -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new RuntimeError(token, "shutdown test release timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeError(token, "shutdown test interrupted");
            }
            respond(args, "finished");
        });
        ServerOps server = new ServerOps(0, 2, 8);
        servers.add(server);
        server.addRoute("GET", "/active", handler, TOKEN);
        server.start(new Interpreter(), TOKEN);

        CompletableFuture<HttpResponse<String>> request = send(server, "GET", "/active", "", Map.of());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> stopping = CompletableFuture.runAsync(server::stop);
        release.countDown();

        assertEquals("finished", request.get(5, TimeUnit.SECONDS).body());
        stopping.get(5, TimeUnit.SECONDS);
        assertFalse(server.isRunning());
        assertEventuallyNoHttpWorkers();
    }

    private CompletableFuture<HttpResponse<String>> send(
            ServerOps server, String method, String path, String body, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + server.getBoundPort() + path))
            .timeout(REQUEST_TIMEOUT);
        headers.forEach(builder::header);
        builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<HttpResponse<String>> await(
            List<CompletableFuture<HttpResponse<String>>> futures) throws Exception {
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(15, TimeUnit.SECONDS);
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private static NativeFunction handler(HandlerBody body) {
        return new NativeFunction("testHandler", 2) {
            @Override
            public Object call(List<Object> args, Token token) {
                body.run(args, token);
                return null;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static void respond(List<Object> args, String body) {
        Map<String, Object> response = (Map<String, Object>) args.get(1);
        NativeFunction text = (NativeFunction) response.get("text");
        text.call(List.of(response, body), TOKEN);
    }

    private static Interpreter compile(String source, Path scriptDir) {
        List<Token> tokens = new Lexer(source).tokenize();
        List<Stmt> statements = new Parser(tokens).parse();
        List<SemanticError> errors = new Resolver().resolve(statements);
        assertTrue(errors.isEmpty(), () -> "semantic errors: " + errors);
        Interpreter interpreter = new Interpreter(new ModuleLoader(scriptDir));
        interpreter.interpret(statements);
        return interpreter;
    }

    private static Object global(Interpreter interpreter, String name) {
        Environment globals = interpreter.getGlobalEnvironment();
        return globals.get(new Token(TokenType.IDENTIFIER, name, null, 1, 1));
    }

    private static void assertEventuallyNoHttpWorkers() throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            boolean present = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith("tlang-http-"));
            if (!present) {
                return;
            }
            Thread.sleep(20);
        }
        fail("HTTP worker threads remained alive after shutdown");
    }

    @FunctionalInterface
    private interface HandlerBody {
        void run(List<Object> args, Token token);
    }
}
