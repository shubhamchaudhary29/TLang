package dev.tlang.runtime.http;

import dev.tlang.ast.Stmt;
import dev.tlang.errors.RuntimeError;
import dev.tlang.errors.RuntimeErrorKind;
import dev.tlang.interpreter.Interpreter;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import dev.tlang.modules.ModuleLoader;
import dev.tlang.parser.Parser;
import dev.tlang.runtime.task.TaskRuntime;
import dev.tlang.runtime.task.TaskValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TaskHttpServerTest {
    private static final Token TOKEN = new Token(TokenType.IDENTIFIER, "test", null, 1, 1);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final List<ServerOps> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(ServerOps::stop);
    }

    @Test
    void simultaneousHandlersRunIsolatedTasksAndReadCopiedRequests(@TempDir Path directory) throws Exception {
        CyclicBarrier allTasksEntered = new CyclicBarrier(4);
        Interpreter interpreter = compile(directory, """
            define load taking req and suffix
                task_gate()
                return req.params.id + ":" + req.body + ":" + req.headers.get("authorization") + ":" + req.headers.get("cookie") + ":" + suffix
            define handle taking req and res
                let first be spawn load(req, "first")
                let second be spawn load(req, "second")
                let a be await first
                let b be await second
                res.text(a + "|" + b)
            """);
        interpreter.getGlobalEnvironment().define("task_gate", barrierFunction(allTasksEntered));

        ServerOps server = new ServerOps(0, 2, 32);
        servers.add(server);
        server.addRoute("POST", "/tasks/:id", global(interpreter, "handle"), TOKEN);
        server.start(interpreter, TOKEN);

        List<HttpResponse<String>> responses = await(List.of(
            send(server, "/tasks/A", "body-A", "token-A", "session=A"),
            send(server, "/tasks/B", "body-B", "token-B", "session=B")
        ));

        assertEquals("A:body-A:token-A:session=A:first|A:body-A:token-A:session=A:second", responses.get(0).body());
        assertEquals("B:body-B:token-B:session=B:first|B:body-B:token-B:session=B:second", responses.get(1).body());
        assertEquals(List.of(200, 200), responses.stream().map(HttpResponse::statusCode).toList());
        assertEquals(0, interpreter.getTaskRuntime().getOutstandingTaskCount());
    }

    @Test
    void responseMutationFromBackgroundTaskFailsWithoutRacingResponse(@TempDir Path directory) throws Exception {
        CountDownLatch releaseTask = new CountDownLatch(1);
        Interpreter interpreter = compile(directory, """
            let backgroundTask be nil
            define mutate taking res
                wait_for_release()
                res.text("late response")
            define handle taking req and res
                set backgroundTask to spawn mutate(res)
                res.text("normal response")
            """);
        interpreter.getGlobalEnvironment().define("wait_for_release", latchFunction(releaseTask));

        ServerOps server = new ServerOps(0, 2, 32);
        servers.add(server);
        server.addRoute("GET", "/ownership", global(interpreter, "handle"), TOKEN);
        server.start(interpreter, TOKEN);

        HttpResponse<String> response = send(
            server, "/ownership", "", "token", "session=test").get(10, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertEquals("normal response", response.body());

        TaskValue task = (TaskValue) global(interpreter, "backgroundTask");
        releaseTask.countDown();
        RuntimeError failure = assertThrows(RuntimeError.class,
            () -> interpreter.getTaskRuntime().await(task, TOKEN));
        assertEquals(RuntimeErrorKind.HTTP_ERROR, failure.getKind());
        assertTrue(failure.getMessage().contains("only be mutated"));
        assertEquals(0, interpreter.getTaskRuntime().getOutstandingTaskCount());
    }

    private CompletableFuture<HttpResponse<String>> send(
            ServerOps server, String path, String body, String authorization, String cookie) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + server.getBoundPort() + path))
            .timeout(TIMEOUT)
            .header("Authorization", authorization)
            .header("Cookie", cookie)
            .method(body.isEmpty() ? "GET" : "POST", body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private static List<HttpResponse<String>> await(
            List<CompletableFuture<HttpResponse<String>>> responses) throws Exception {
        CompletableFuture.allOf(responses.toArray(CompletableFuture[]::new))
            .get(10, TimeUnit.SECONDS);
        List<HttpResponse<String>> completed = new ArrayList<>();
        for (CompletableFuture<HttpResponse<String>> response : responses) {
            completed.add(response.join());
        }
        return completed;
    }

    private static Interpreter compile(Path directory, String source) {
        Interpreter interpreter = new Interpreter(new ModuleLoader(directory), new TaskRuntime());
        List<Stmt> program = new Parser(new Lexer(source, "task-http.tiny").tokenize()).parse();
        interpreter.interpret(program);
        return interpreter;
    }

    private static Object global(Interpreter interpreter, String name) {
        return interpreter.getGlobalEnvironment().get(
            new Token(TokenType.IDENTIFIER, name, null, 1, 1));
    }

    private static NativeFunction barrierFunction(CyclicBarrier barrier) {
        return new NativeFunction("task_gate", 0) {
            @Override
            public Object call(List<Object> args, Token token) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new RuntimeError(token, "Task barrier failed.", failure);
                }
                return null;
            }
        };
    }

    private static NativeFunction latchFunction(CountDownLatch latch) {
        return new NativeFunction("wait_for_release", 0) {
            @Override
            public Object call(List<Object> args, Token token) {
                try {
                    if (!latch.await(5, TimeUnit.SECONDS)) {
                        throw new RuntimeError(token, "Task latch timed out.");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeError(token, "Task latch interrupted.", interrupted);
                }
                return null;
            }
        };
    }
}
