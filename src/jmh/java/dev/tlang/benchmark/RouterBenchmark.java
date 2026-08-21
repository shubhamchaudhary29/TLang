package dev.tlang.benchmark;

import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import dev.tlang.runtime.http.Route;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class RouterBenchmark {
    private Route route;
    private List<String> requestSegments;

    @Setup
    public void setUp() {
        Token token = new Token(TokenType.STRING, "route", null, 1, 1);
        route = new Route("GET", "/api/users/:userId/posts/:postId", new Object(), token);
        requestSegments = Route.getSegments("/api/users/42/posts/7");
        if (!route.matches("GET", requestSegments) || route.matches("POST", requestSegments)
                || route.specificityScore() != 3) {
            throw new IllegalStateException("Router benchmark fixture is invalid");
        }
    }

    @Benchmark
    public boolean parameterizedRouteMatch() {
        return route.matches("GET", requestSegments);
    }

    @Benchmark
    public List<String> normalizeAndSplitPath() {
        return Route.getSegments(Route.normalizePath("api/users/42/posts/7/"));
    }
}
