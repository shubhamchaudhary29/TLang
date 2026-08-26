package dev.tlang.benchmark;

import dev.tlang.interpreter.Interpreter;
import dev.tlang.interpreter.NativeFunction;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import dev.tlang.runtime.http.ServerOps;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Measures a complete loopback HTTP batch, including concurrent handler execution. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class ConcurrentHttpBenchmark {
    private static final Token TOKEN = new Token(TokenType.IDENTIFIER, "benchmark", null, 1, 1);

    @Param({"1", "10", "50", "100"})
    public int concurrency;

    private ServerOps server;
    private HttpClient client;
    private URI endpoint;

    @Setup(Level.Trial)
    public void setUp() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        server = new ServerOps(0, 16, 256);
        server.addRoute("GET", "/small", new NativeFunction("smallResponse", 2) {
            @Override
            @SuppressWarnings("unchecked")
            public Object call(List<Object> args, Token token) {
                Map<String, Object> response = (Map<String, Object>) args.get(1);
                NativeFunction text = (NativeFunction) response.get("text");
                text.call(List.of(response, "ok"), token);
                return null;
            }
        }, TOKEN);
        server.start(new Interpreter(), TOKEN);
        endpoint = URI.create("http://127.0.0.1:" + server.getBoundPort() + "/small");

        // Fail setup rather than record timings for a broken fixture.
        try {
            HttpResponse<String> response = client.send(request(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !response.body().equals("ok")) {
                throw new IllegalStateException("Concurrent HTTP benchmark fixture returned an invalid response");
            }
        } catch (Exception e) {
            server.stop();
            throw new IllegalStateException("Concurrent HTTP benchmark fixture failed", e);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        server.stop();
    }

    /** Average milliseconds for one batch of {@link #concurrency} simultaneous requests. */
    @Benchmark
    public int loopbackBatch() {
        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>(concurrency);
        for (int i = 0; i < concurrency; i++) {
            requests.add(client.sendAsync(request(), HttpResponse.BodyHandlers.ofString()));
        }
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();

        int correct = 0;
        for (CompletableFuture<HttpResponse<String>> request : requests) {
            HttpResponse<String> response = request.join();
            if (response.statusCode() == 200 && response.body().equals("ok")) {
                correct++;
            }
        }
        if (correct != concurrency) {
            throw new IllegalStateException("HTTP batch had " + (concurrency - correct) + " failed/corrupt responses");
        }
        return correct;
    }

    private HttpRequest request() {
        return HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
    }
}
