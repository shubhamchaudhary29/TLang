package dev.tlang.runtime.database;

final class SqliteRepositoryTest extends RepositoryContract {
    @org.junit.jupiter.api.Test void fileBackedRepositoryPersists(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        var options = new DatabaseOptions(directory.resolve("repository.sqlite").toString(), null, null, 1, 5000, 5);
        var first = new SqliteProvider().open(options);
        try {
            first.execute("CREATE TABLE persisted_repo (id INTEGER PRIMARY KEY, name TEXT)", java.util.List.of());
            DatabaseRepository.define(first, "persisted_repo", java.util.Map.of("primaryKey", "id",
                "fields", java.util.List.of("id", "name"))).create(java.util.Map.of("id", 1, "name", "saved"));
        } finally { first.close(); }
        var second = new SqliteProvider().open(options);
        try {
            var repository = DatabaseRepository.define(second, "persisted_repo", java.util.Map.of("primaryKey", "id",
                "fields", java.util.List.of("id", "name")));
            org.junit.jupiter.api.Assertions.assertEquals(java.util.Map.of("id", 1, "name", "saved"), repository.find(1));
        } finally { second.close(); }
    }

    @Override protected DatabaseConnection open() {
        return new SqliteProvider().open(new DatabaseOptions(":memory:", null, null, 1, 5000, 5));
    }
}
