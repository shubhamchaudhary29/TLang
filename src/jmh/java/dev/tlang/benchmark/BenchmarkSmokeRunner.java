package dev.tlang.benchmark;

import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

/** Fast JMH discovery/execution check. Scores from this mode are not baselines. */
public final class BenchmarkSmokeRunner {
    private BenchmarkSmokeRunner() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: BenchmarkSmokeRunner <result.json>");
        }
        Path result = BenchmarkOutput.prepare(args[0]);
        Options options = new OptionsBuilder()
            .include("dev.tlang.benchmark.*Benchmark.*")
            .shouldFailOnError(true)
            .warmupIterations(0)
            .measurementIterations(1)
            .measurementTime(TimeValue.milliseconds(5))
            .forks(0)
            .resultFormat(ResultFormatType.JSON)
            .result(result.toString())
            .build();
        Collection<RunResult> results = new Runner(options).run();
        if (results.isEmpty() || !Files.isRegularFile(result) || Files.size(result) == 0) {
            throw new IllegalStateException("JMH smoke run did not produce structured results");
        }
        System.out.println("Benchmark smoke passed: " + results.size() + " benchmark cases");
    }
}
