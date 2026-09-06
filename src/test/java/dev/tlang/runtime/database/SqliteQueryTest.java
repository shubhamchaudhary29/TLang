package dev.tlang.runtime.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class SqliteQueryTest extends QueryContract {
    @Override protected DatabaseConnection open() {
        return new SqliteProvider().open(new DatabaseOptions(":memory:", null, null, 1, 5000, 5));
    }

    @Test void fileBackedPersistence(@TempDir Path directory) {
        var options = new DatabaseOptions(directory.resolve("query.sqlite").toString(), null, null, 1, 5000, 5);
        var query = DatabaseQuery.table("persisted");
        var first = new SqliteProvider().open(options);
        try {
            first.execute("CREATE TABLE persisted (id INTEGER)", List.of());
            query.insert(first, Map.of("id", 42));
        } finally { first.close(); }
        var second = new SqliteProvider().open(options);
        try { assertEquals(Map.of("id", 42), query.first(second)); }
        finally { second.close(); }
    }
}
