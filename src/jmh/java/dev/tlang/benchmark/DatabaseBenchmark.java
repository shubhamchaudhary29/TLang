package dev.tlang.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class DatabaseBenchmark {
    private TemporaryWorkspace workspace;
    private BenchmarkProgram program;

    @Setup(Level.Trial)
    public void validate() {
        BenchmarkProgram inMemory = BenchmarkProgram.compile(
            "database-validation", source(":memory:"), Path.of("."));
        inMemory.validateResult(25);
    }

    @Setup(Level.Invocation)
    public void setUpInvocation() {
        workspace = TemporaryWorkspace.create();
        Path database = workspace.directory().resolve("benchmark data.sqlite");
        String escapedPath = database.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        program = BenchmarkProgram.compile("database", source(escapedPath), workspace.directory());
    }

    @TearDown(Level.Invocation)
    public void tearDownInvocation() {
        workspace.close();
    }

    @Benchmark
    public Object sqliteRoundTrip() {
        return program.execute();
    }

    private static String source(String path) {
        return """
            import db
            let connection be db.open("%s")
            connection.execute("CREATE TABLE values_table (value INTEGER)", [])
            repeat 10 times as index
              connection.insert("INSERT INTO values_table (value) VALUES (?)", [index])
            let rows be connection.query("SELECT count(*) AS count FROM values_table", [])
            let result be rows.get(0).get("count") + 15
            connection.close()
            """.formatted(path);
    }
}
