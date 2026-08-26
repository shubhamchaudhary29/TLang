package dev.tlang.runtime.task;

import com.sun.net.httpserver.HttpServer;
import dev.tlang.ast.Stmt;
import dev.tlang.errors.ErrorFormatter;
import dev.tlang.errors.RuntimeError;
import dev.tlang.errors.RuntimeErrorKind;
import dev.tlang.errors.StackFrameType;
import dev.tlang.interpreter.Interpreter;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.lexer.Lexer;
import dev.tlang.lexer.SourceUnit;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import dev.tlang.modules.ModuleLoader;
import dev.tlang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TaskRuntimeTest {
    private static final Token AWAIT_TOKEN = token("await", "test.tiny", "await task\n");

    @Test
    void spawnReturnsOpaqueTaskAndAwaitReturnsReusableResult(@TempDir Path directory) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Interpreter interpreter = interpreter(directory, new TaskRuntime());
        define(interpreter, "block", blockingFunction(release, 42));

        interpret(interpreter, "let task be spawn block()\n");
        TaskValue task = (TaskValue) global(interpreter, "task");
        interpret(interpreter, "let taskType be type_of(task)\n");

        assertEquals("task", dev.tlang.types.Type.of(task).displayName());
        assertEquals("task", global(interpreter, "taskType"));
        assertTrue(Set.of(TaskState.PENDING, TaskState.RUNNING).contains(task.state()));
        assertTrue(task.toString().equals("<task pending>") || task.toString().equals("<task running>"));

        release.countDown();
        assertEquals(42, interpreter.getTaskRuntime().await(task, AWAIT_TOKEN));
        assertEquals(42, interpreter.getTaskRuntime().await(task, AWAIT_TOKEN));
        assertEquals(TaskState.SUCCEEDED, task.state());
        assertEquals("<task completed>", task.toString());
        assertEquals(0, interpreter.getTaskRuntime().getOutstandingTaskCount());
    }

    @Test
    void twoTasksOverlapAtDeterministicBarrier(@TempDir Path directory) {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> threadNames = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Boolean> virtualThreads = java.util.Collections.synchronizedList(new ArrayList<>());
        Interpreter interpreter = interpreter(directory, new TaskRuntime());
        define(interpreter, "meet", new NativeFunction("meet", 0) {
            @Override
            public Object call(List<Object> args, Token token) {
                threadNames.add(Thread.currentThread().getName());
                virtualThreads.add(Thread.currentThread().isVirtual());
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new RuntimeError(token, "Task barrier failed.", failure);
                }
                return null;
            }
        });

        interpret(interpreter, """
            define work taking value
                meet()
                return value
            let first be spawn work(1)
            let second be spawn work(2)
            let a be await first
            let b be await second
            """);

        assertEquals(1, global(interpreter, "a"));
        assertEquals(2, global(interpreter, "b"));
        assertEquals(2, threadNames.size());
        assertTrue(threadNames.stream().allMatch(name -> name.startsWith("tlang-task-")));
        assertNotEquals(threadNames.get(0), threadNames.get(1));
        assertEquals(List.of(true, true), virtualThreads);
    }

    @Test
    @SuppressWarnings("unchecked")
    void calleeAndArgumentsEvaluateSynchronouslyBeforeScheduling(@TempDir Path directory) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Interpreter interpreter = interpreter(directory, new TaskRuntime());
        define(interpreter, "hold", blockingFunction(release, null));

        interpret(interpreter, """
            let order be []
            define work taking value
                hold()
                return value
            define choose
                order.add("callee")
                return work
            define argument
                order.add("argument")
                return 7
            let task be spawn choose()(argument())
            """);

        assertEquals(List.of("callee", "argument"), List.copyOf((List<Object>) global(interpreter, "order")));
        release.countDown();
        assertEquals(7, interpreter.getTaskRuntime().await(
            (TaskValue) global(interpreter, "task"), AWAIT_TOKEN));
    }

    @Test
    void closuresNestedTasksAndAwaitSpawnWork(@TempDir Path directory) {
        Interpreter interpreter = interpreter(directory, new TaskRuntime());
        interpret(interpreter, """
            define createTask
                let prefix be "hello"
                define work taking name
                    return prefix + " " + name
                return spawn work("TLang")
            define child
                return 42
            define parent
                return await spawn child()
            define returnTask
                return spawn child()
            let closureResult be await createTask()
            let nestedResult be await spawn parent()
            let innerTask be await spawn returnTask()
            let taskResult be await innerTask
            """);

        assertEquals("hello TLang", global(interpreter, "closureResult"));
        assertEquals(42, global(interpreter, "nestedResult"));
        assertEquals(42, global(interpreter, "taskResult"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void oneHundredNestedTaskTreesCannotStarve(@TempDir Path directory) {
        TaskRuntime runtime = new TaskRuntime(250);
        Interpreter interpreter = interpreter(directory, runtime);
        interpret(interpreter, """
            let tasks be []
            define child taking value
                return value
            define parent taking value
                return await spawn child(value)
            repeat 100 times as index
                tasks.add(spawn parent(index))
            """);

        List<Object> tasks = (List<Object>) global(interpreter, "tasks");
        for (int index = 0; index < 100; index++) {
            assertEquals(index, runtime.await((TaskValue) tasks.get(index), AWAIT_TOKEN));
        }
        assertEquals(0, runtime.getOutstandingTaskCount());
    }

    @Test
    void manyWaitersObserveTheSameSingleCompletion(@TempDir Path directory) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch waitersReady = new CountDownLatch(20);
        Interpreter interpreter = interpreter(directory, new TaskRuntime());
        define(interpreter, "block", blockingFunction(release, 73));
        interpret(interpreter, "let task be spawn block()\n");
        TaskValue task = (TaskValue) global(interpreter, "task");

        try (ExecutorService waiters = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Object>> results = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                results.add(waiters.submit(() -> {
                    waitersReady.countDown();
                    return interpreter.getTaskRuntime().await(task, AWAIT_TOKEN);
                }));
            }
            assertTrue(waitersReady.await(5, TimeUnit.SECONDS));
            release.countDown();
            for (Future<Object> result : results) {
                assertEquals(73, result.get(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(0, interpreter.getTaskRuntime().getOutstandingTaskCount());
    }

    @Test
    void failedTaskKeepsCategoryLocationFramesAndFreshAwaitFrames(@TempDir Path directory) {
        String source = """
            define inner
                return 1 / 0
            define outer
                return inner()
            let task be spawn outer()
            let result be await task
            """;
        Interpreter interpreter = interpreter(directory, new TaskRuntime());

        RuntimeError first = assertThrows(RuntimeError.class, () -> interpret(interpreter, source, "failure.tiny"));
        TaskValue task = (TaskValue) global(interpreter, "task");
        RuntimeError second = assertThrows(RuntimeError.class,
            () -> interpreter.getTaskRuntime().await(task, AWAIT_TOKEN));

        assertEquals(RuntimeErrorKind.RUNTIME_ERROR, first.getKind());
        assertEquals(2, first.getLocation().line());
        assertEquals(List.of("inner", "outer", "<spawn>", "<await>"),
            first.getFrames().stream().map(frame -> frame.name()).toList());
        assertEquals(StackFrameType.TASK_SPAWN, first.getFrames().get(2).type());
        assertEquals(StackFrameType.TASK_AWAIT, first.getFrames().get(3).type());
        assertEquals(4, second.getFrames().size());
        assertNotSame(first, second);
        String formatted = ErrorFormatter.format(first);
        assertTrue(formatted.contains("RuntimeError: Division by zero."));
        assertFalse(formatted.contains("java."));
        assertFalse(formatted.contains("dev.tlang"));
    }

    @Test
    void awaitNonTaskAndUnderlyingKindsRemainStructured(@TempDir Path directory) {
        Interpreter nonTask = interpreter(directory, new TaskRuntime());
        RuntimeError type = assertThrows(RuntimeError.class,
            () -> interpret(nonTask, "let value be await 123\n", "types.tiny"));
        assertEquals(RuntimeErrorKind.TYPE_ERROR, type.getKind());
        assertEquals(1, type.getLocation().line());

        Interpreter nameInterpreter = interpreter(directory, new TaskRuntime());
        RuntimeError name = assertThrows(RuntimeError.class, () -> interpret(nameInterpreter, """
            define fail
                return missing
            let result be await spawn fail()
            """, "name.tiny"));
        assertEquals(RuntimeErrorKind.NAME_ERROR, name.getKind());

        Interpreter nestedTypeInterpreter = interpreter(directory, new TaskRuntime());
        RuntimeError nestedType = assertThrows(RuntimeError.class, () -> interpret(nestedTypeInterpreter, """
            define fail
                let value be 1
                return value()
            let result be await spawn fail()
            """, "type.tiny"));
        assertEquals(RuntimeErrorKind.TYPE_ERROR, nestedType.getKind());
    }

    @Test
    void ordinaryHostExceptionBecomesSanitizedTaskError(@TempDir Path directory) {
        Interpreter interpreter = interpreter(directory, new TaskRuntime());
        define(interpreter, "host_fail", new NativeFunction("host_fail", 0) {
            @Override
            public Object call(List<Object> args, Token token) {
                throw new IllegalStateException("host secret");
            }
        });

        RuntimeError error = assertThrows(RuntimeError.class,
            () -> interpret(interpreter, "let result be await spawn host_fail()\n"));

        assertEquals(RuntimeErrorKind.TASK_ERROR, error.getKind());
        assertInstanceOf(IllegalStateException.class, error.getCause());
        String formatted = ErrorFormatter.format(error);
        assertTrue(formatted.contains("TaskError"));
        assertFalse(formatted.contains("IllegalStateException"));
        assertFalse(formatted.contains("host secret"));
        assertFalse(formatted.contains("java."));
        assertEquals(0, interpreter.getTaskRuntime().getOutstandingTaskCount());
    }

    @Test
    void taskLimitRejectsThenReleasesCapacity(@TempDir Path directory) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        TaskRuntime runtime = new TaskRuntime(2);
        Interpreter interpreter = interpreter(directory, runtime);
        define(interpreter, "block", blockingFunction(release, 1));

        RuntimeError limit = assertThrows(RuntimeError.class, () -> interpret(interpreter, """
            define work
                return block()
            let first be spawn work()
            let second be spawn work()
            let third be spawn work()
            """, "limit.tiny"));
        assertEquals(RuntimeErrorKind.TASK_ERROR, limit.getKind());
        assertTrue(limit.getMessage().contains("limit of 2"));
        assertEquals(2, runtime.getOutstandingTaskCount());

        release.countDown();
        runtime.await((TaskValue) global(interpreter, "first"), AWAIT_TOKEN);
        runtime.await((TaskValue) global(interpreter, "second"), AWAIT_TOKEN);
        assertEquals(0, runtime.getOutstandingTaskCount());

        interpret(interpreter, "define quick\n    return 9\nlet afterLimit be await spawn quick()\n");
        assertEquals(9, global(interpreter, "afterLimit"));
        assertEquals(0, runtime.getOutstandingTaskCount());
    }

    @Test
    void directAndMutualAwaitCyclesBecomeTaskError(@TempDir Path directory) {
        CountDownLatch selfGate = new CountDownLatch(1);
        Interpreter selfInterpreter = interpreter(directory, new TaskRuntime());
        define(selfInterpreter, "wait_gate", latchAwait(selfGate));
        define(selfInterpreter, "open_gate", latchRelease(selfGate));

        RuntimeError self = assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> assertThrows(RuntimeError.class, () -> interpret(selfInterpreter, """
                let selfTask be nil
                define selfWait
                    wait_gate()
                    return await selfTask
                set selfTask to spawn selfWait()
                open_gate()
                let result be await selfTask
                """, "self-cycle.tiny")));
        assertEquals(RuntimeErrorKind.TASK_ERROR, self.getKind());
        assertTrue(self.getMessage().contains("cycle"));

        CountDownLatch pairGate = new CountDownLatch(1);
        Interpreter pairInterpreter = interpreter(directory, new TaskRuntime());
        define(pairInterpreter, "wait_pair", latchAwait(pairGate));
        define(pairInterpreter, "open_pair", latchRelease(pairGate));
        RuntimeError pair = assertTimeoutPreemptively(Duration.ofSeconds(5),
            () -> assertThrows(RuntimeError.class, () -> interpret(pairInterpreter, """
                let firstTask be nil
                let secondTask be nil
                define first
                    wait_pair()
                    return await secondTask
                define second
                    wait_pair()
                    return await firstTask
                set firstTask to spawn first()
                set secondTask to spawn second()
                open_pair()
                let result be await firstTask
                """, "pair-cycle.tiny")));
        assertEquals(RuntimeErrorKind.TASK_ERROR, pair.getKind());
        assertTrue(pair.getMessage().contains("cycle"));
        // The task awaited by the script may finish before its peer has propagated
        // the same cycle failure. Join the peer before asserting lifecycle cleanup.
        assertThrows(RuntimeError.class, () -> pairInterpreter.getTaskRuntime().await(
            (TaskValue) global(pairInterpreter, "secondTask"), AWAIT_TOKEN));
        assertEquals(0, selfInterpreter.getTaskRuntime().getOutstandingTaskCount());
        assertEquals(0, pairInterpreter.getTaskRuntime().getOutstandingTaskCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fiveHundredMixedTasksCompleteWithoutStateOrFrameContamination(@TempDir Path directory) {
        TaskRuntime runtime = new TaskRuntime(600);
        Interpreter interpreter = interpreter(directory, runtime);
        interpret(interpreter, """
            let shared be []
            let successes be []
            let failures be []
            define identity taking value
                shared.add(value)
                return value
            define explode taking value
                return value / 0
            repeat 250 times as i
                successes.add(spawn identity(i))
                failures.add(spawn explode(i))
            """, "stress.tiny");

        List<Object> successes = (List<Object>) global(interpreter, "successes");
        List<Object> failures = (List<Object>) global(interpreter, "failures");
        List<Object> shared = (List<Object>) global(interpreter, "shared");
        for (int index = 0; index < 250; index++) {
            assertEquals(index, runtime.await((TaskValue) successes.get(index), AWAIT_TOKEN));
        }
        int failureCount = 0;
        for (Object value : failures) {
            RuntimeError error = assertThrows(RuntimeError.class,
                () -> runtime.await((TaskValue) value, AWAIT_TOKEN));
            assertEquals(RuntimeErrorKind.RUNTIME_ERROR, error.getKind());
            assertEquals(List.of("explode", "<spawn>", "<await>"),
                error.getFrames().stream().map(frame -> frame.name()).toList());
            failureCount++;
        }
        assertEquals(250, successes.size());
        assertEquals(250, shared.size());
        assertEquals(java.util.stream.IntStream.range(0, 250).boxed().collect(java.util.stream.Collectors.toSet()),
            Set.copyOf(shared));
        assertEquals(250, failureCount);
        assertEquals(0, runtime.getOutstandingTaskCount());
    }

    @Test
    void exportedModuleFunctionsAndClosuresRunInTasks(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("task_math.tiny"), """
            define increment taking value
                return value + 1
            """);
        Files.writeString(directory.resolve("service.tiny"), """
            import task_math
            let initializedTask be spawn task_math.increment(9)
            let initialized be await initializedTask
            define calculate taking value
                return task_math.increment(value)
            define make
                let prefix be "module"
                define exported taking value
                    return prefix + ":" + value
                return exported
            let closure be make()
            define crash
                return 1 / 0
            """);
        Interpreter interpreter = interpreter(directory, new TaskRuntime());
        interpret(interpreter, """
            import service
            let number be await spawn service.calculate(41)
            let text be await spawn service.closure("ok")
            let failed be spawn service.crash()
            """, directory.resolve("main.tiny").toString());

        assertEquals(42, global(interpreter, "number"));
        assertEquals("module:ok", global(interpreter, "text"));
        @SuppressWarnings("unchecked")
        Map<String, Object> service = (Map<String, Object>) global(interpreter, "service");
        assertEquals(10, service.get("initialized"));
        RuntimeError failure = assertThrows(RuntimeError.class,
            () -> interpreter.getTaskRuntime().await(
                (TaskValue) global(interpreter, "failed"), AWAIT_TOKEN));
        assertTrue(failure.getLocation().sourceName().endsWith("service.tiny"));
        assertEquals(RuntimeErrorKind.RUNTIME_ERROR, failure.getKind());
    }

    @Test
    void concurrentFirstImportFromTasksPublishesOneCompleteModule(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("task_service.tiny"), """
            define calculate taking value
                return value + 1
            """);
        CyclicBarrier importGate = new CyclicBarrier(2);
        Interpreter interpreter = interpreter(directory, new TaskRuntime());
        define(interpreter, "import_gate", barrierFunction(importGate));

        interpret(interpreter, """
            define load taking value
                import_gate()
                import task_service
                return task_service.calculate(value)
            let first be spawn load(40)
            let second be spawn load(41)
            let a be await first
            let b be await second
            """, directory.resolve("main.tiny").toString());

        assertEquals(41, global(interpreter, "a"));
        assertEquals(42, global(interpreter, "b"));
        assertEquals(0, interpreter.getTaskRuntime().getOutstandingTaskCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sqliteCallsSerializeAndDatabaseErrorSurvivesAwait(@TempDir Path directory) {
        String dbPath = directory.resolve("tasks.sqlite").toString().replace("\\", "\\\\");
        Interpreter interpreter = interpreter(directory, new TaskRuntime());
        interpret(interpreter, """
            import db
            let connection be db.open("%s")
            connection.execute("CREATE TABLE items (value INTEGER)", [])
            let first be spawn connection.execute("INSERT INTO items(value) VALUES (?)", [1])
            let second be spawn connection.execute("INSERT INTO items(value) VALUES (?)", [2])
            await first
            await second
            let readFirst be spawn connection.query("SELECT value FROM items ORDER BY value", [])
            let readSecond be spawn connection.query("SELECT value FROM items ORDER BY value", [])
            let rows be await readFirst
            let otherRows be await readSecond
            connection.close()
            let failed be spawn connection.query("SELECT value FROM items", [])
            """.formatted(dbPath), "database-task.tiny");

        List<Object> rows = (List<Object>) global(interpreter, "rows");
        assertEquals(2, rows.size());
        assertEquals(2, ((List<Object>) global(interpreter, "otherRows")).size());
        RuntimeError failure = assertThrows(RuntimeError.class,
            () -> interpreter.getTaskRuntime().await(
                (TaskValue) global(interpreter, "failed"), AWAIT_TOKEN));
        assertEquals(RuntimeErrorKind.DATABASE_ERROR, failure.getKind());
        assertEquals(0, interpreter.getTaskRuntime().getOutstandingTaskCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoHttpClientTasksOverlapAndHttpFailureRetainsCategory(@TempDir Path directory) throws Exception {
        CyclicBarrier requestsEntered = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(executor);
        server.createContext("/data", exchange -> {
            try {
                requestsEntered.await(5, TimeUnit.SECONDS);
                byte[] body = exchange.getRequestURI().getQuery().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            } finally {
                exchange.close();
            }
        });
        server.createContext("/missing", exchange -> {
            byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Interpreter interpreter = interpreter(directory, new TaskRuntime());
            interpret(interpreter, """
                import http
                let first be spawn http.get("http://127.0.0.1:%d/data?first")
                let second be spawn http.get("http://127.0.0.1:%d/data?second")
                let firstResponse be await first
                let secondResponse be await second
                let missingResponse be await spawn http.get("http://127.0.0.1:%d/missing")
                let failed be spawn http.get("ftp://example.com")
                """.formatted(port, port, port), "http-task.tiny");

            assertEquals("first", ((Map<String, Object>) global(interpreter, "firstResponse")).get("body"));
            assertEquals("second", ((Map<String, Object>) global(interpreter, "secondResponse")).get("body"));
            Map<String, Object> missing = (Map<String, Object>) global(interpreter, "missingResponse");
            assertEquals(404, missing.get("status"));
            assertEquals(false, missing.get("ok"));
            RuntimeError failure = assertThrows(RuntimeError.class,
                () -> interpreter.getTaskRuntime().await(
                    (TaskValue) global(interpreter, "failed"), AWAIT_TOKEN));
            assertEquals(RuntimeErrorKind.HTTP_ERROR, failure.getKind());
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static Interpreter interpreter(Path directory, TaskRuntime runtime) {
        return new Interpreter(new ModuleLoader(directory), runtime);
    }

    private static void interpret(Interpreter interpreter, String source) {
        interpret(interpreter, source, "task-test.tiny");
    }

    private static void interpret(Interpreter interpreter, String source, String sourceName) {
        List<Stmt> statements = new Parser(new Lexer(source, sourceName).tokenize()).parse();
        interpreter.interpret(statements);
    }

    private static Object global(Interpreter interpreter, String name) {
        return interpreter.getGlobalEnvironment().get(token(name, "test.tiny", name + "\n"));
    }

    private static void define(Interpreter interpreter, String name, NativeFunction function) {
        interpreter.getGlobalEnvironment().define(name, function);
    }

    private static NativeFunction blockingFunction(CountDownLatch release, Object result) {
        return new NativeFunction("block", 0) {
            @Override
            public Object call(List<Object> args, Token token) {
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new RuntimeError(token, "Task latch timed out.");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeError(token, "Task latch interrupted.", interrupted);
                }
                return result;
            }
        };
    }

    private static NativeFunction latchAwait(CountDownLatch latch) {
        return blockingFunction(latch, null);
    }

    private static NativeFunction latchRelease(CountDownLatch latch) {
        return new NativeFunction("open_gate", 0) {
            @Override
            public Object call(List<Object> args, Token token) {
                latch.countDown();
                return null;
            }
        };
    }

    private static NativeFunction barrierFunction(CyclicBarrier barrier) {
        return new NativeFunction("pair_gate", 0) {
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

    private static Token token(String lexeme, String sourceName, String source) {
        return new Token(TokenType.IDENTIFIER, lexeme, null, 1, 1,
            new SourceUnit(sourceName, source));
    }
}
