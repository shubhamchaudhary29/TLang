package dev.tlang.benchmark;

import dev.tlang.runtime.database.DatabaseConnection;
import dev.tlang.runtime.database.DatabaseOptions;
import dev.tlang.runtime.database.DatabaseRepository;
import dev.tlang.runtime.database.RepositoryDefinition;
import dev.tlang.runtime.database.SqliteProvider;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class RepositoryBenchmark {
    private static final Map<String, Object> DEFINITION = Map.of("primaryKey", "id", "fields", List.of("id", "name"));
    private DatabaseConnection connection;
    private DatabaseRepository repository;

    @Setup(Level.Trial) public void setup() {
        connection = new SqliteProvider().open(new DatabaseOptions(":memory:", null, null, 1, 5000, 5));
        connection.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)", List.of());
        repository = DatabaseRepository.define(connection, "users", DEFINITION);
        repository.create(Map.of("id", 1, "name", "Ada"));
        if (!Map.of("id", 1, "name", "Ada").equals(repository.find(1)) || !repository.exists(1)) {
            throw new IllegalStateException("Repository benchmark validation failed.");
        }
    }

    @Benchmark public Object definition() { return RepositoryDefinition.parse("users", DEFINITION); }
    @Benchmark public Object find() { return repository.find(1); }
    @TearDown(Level.Trial) public void close() { connection.close(); }
}
