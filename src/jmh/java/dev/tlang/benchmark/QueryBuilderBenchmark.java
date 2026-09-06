package dev.tlang.benchmark;

import dev.tlang.runtime.database.DatabaseConnection;
import dev.tlang.runtime.database.DatabaseOptions;
import dev.tlang.runtime.database.DatabaseQuery;
import dev.tlang.runtime.database.SqliteProvider;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class QueryBuilderBenchmark {
    private DatabaseConnection connection;
    private DatabaseQuery base;

    @Setup(Level.Trial) public void setup() {
        connection = new SqliteProvider().open(new DatabaseOptions(":memory:", null, null, 1, 5000, 5));
        connection.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)", List.of());
        base = DatabaseQuery.table("users");
        base.insert(connection, Map.of("id", 1, "name", "Ada"));
        if (base.count(connection) != 1 || !Map.of("id", 1, "name", "Ada").equals(base.first(connection))) {
            throw new IllegalStateException("Query benchmark validation failed.");
        }
    }

    @Benchmark public Object compile() {
        return base.select(List.of("id", "name")).where("id", "=", 1).orderBy("id", "asc").limit(20).compileAll();
    }

    @Benchmark public Object sqliteRead() { return base.where("id", "=", 1).first(connection); }

    @TearDown(Level.Trial) public void close() { connection.close(); }
}
