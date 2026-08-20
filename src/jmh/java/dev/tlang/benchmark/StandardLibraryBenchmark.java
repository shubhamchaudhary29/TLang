package dev.tlang.benchmark;

import dev.tlang.interpreter.NativeFunction;
import dev.tlang.lexer.Token;
import dev.tlang.lexer.TokenType;
import dev.tlang.modules.JsonModule;
import dev.tlang.modules.StringsModule;
import dev.tlang.runtime.json.JsonParser;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class StandardLibraryBenchmark {
    private static final Token TOKEN = new Token(TokenType.IDENTIFIER, "benchmark", null, 1, 1);
    private static final String JSON =
        "{\"name\":\"TLang\",\"active\":true,\"values\":[1,2,3,4,5],\"nested\":{\"count\":42}}";

    private Map<String, Object> jsonValue;
    private NativeFunction join;
    private List<Object> joinArguments;

    @Setup
    public void setUp() {
        jsonValue = new LinkedHashMap<>();
        jsonValue.put("name", "TLang");
        jsonValue.put("active", true);
        jsonValue.put("values", List.of(1, 2, 3, 4, 5));
        jsonValue.put("nested", new LinkedHashMap<>(Map.of("count", 42)));
        join = (NativeFunction) new StringsModule().getExports().get("join");
        joinArguments = List.of(List.of("alpha", "beta", "gamma", 42), ",");

        Object parsed = new JsonParser(JSON, TOKEN).parse();
        if (!jsonValue.equals(parsed)) {
            throw new IllegalStateException("JSON parse benchmark has an unexpected result");
        }
        if (!JSON.equals(JsonModule.jsonStringifyExternal(jsonValue, TOKEN))) {
            throw new IllegalStateException("JSON stringify benchmark has an unexpected result");
        }
        if (!"alpha,beta,gamma,42".equals(join.call(joinArguments, TOKEN))) {
            throw new IllegalStateException("String join benchmark has an unexpected result");
        }
    }

    @Benchmark
    public Object jsonParse() {
        return new JsonParser(JSON, TOKEN).parse();
    }

    @Benchmark
    public String jsonStringify() {
        return JsonModule.jsonStringifyExternal(jsonValue, TOKEN);
    }

    @Benchmark
    public Object stringJoin() {
        return join.call(joinArguments, TOKEN);
    }
}
