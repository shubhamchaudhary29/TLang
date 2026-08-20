package dev.tlang.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class RuntimeBenchmark {
    private static final Map<String, Object> EXPECTED = Map.of(
        "arithmetic", 17,
        "loops", 19900,
        "function_calls", 4950,
        "recursion", 3628800,
        "closures", 42,
        "lists", 4950,
        "maps", 101,
        "string_operations", "TINYTINYTINYTINYTINY"
    );

    @Param({
        "arithmetic", "loops", "function_calls", "recursion",
        "closures", "lists", "maps", "string_operations"
    })
    public String workload;

    private BenchmarkProgram program;

    @Setup
    public void setUp() {
        program = BenchmarkProgram.load(workload);
        program.validateResult(EXPECTED.get(workload));
    }

    @Benchmark
    public Object interpret() {
        return program.execute();
    }
}
