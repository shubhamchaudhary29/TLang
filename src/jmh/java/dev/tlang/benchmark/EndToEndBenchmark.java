package dev.tlang.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class EndToEndBenchmark {
    private BenchmarkProgram program;

    @Setup
    public void setUp() {
        program = BenchmarkProgram.load("end_to_end");
        program.validateResult(2450);
    }

    /** Warming JVM, source already in memory; includes lex, parse, resolve and execute. */
    @Benchmark
    public Object warmedFullPipeline() {
        return program.executeFullPipeline();
    }
}
