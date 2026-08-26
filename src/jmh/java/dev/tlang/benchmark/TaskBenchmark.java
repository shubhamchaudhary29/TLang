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

/** End-to-end task costs, including creation of a fresh interpreter runtime. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class TaskBenchmark {
    private static final Map<String, Object> EXPECTED = Map.of(
        "task_sync_call", 1,
        "task_spawn_await", 1,
        "task_batch", 36,
        "task_repeated_await", 1
    );

    @Param({
        "task_sync_call", "task_spawn_await", "task_batch", "task_repeated_await"
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
