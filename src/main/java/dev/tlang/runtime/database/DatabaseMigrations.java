package dev.tlang.runtime.database;

import dev.tlang.interpreter.RuntimeCollections;

import java.util.List;
import java.util.Map;

/** Public facade used by the native db handle without exposing JDBC internals. */
public final class DatabaseMigrations {
    private DatabaseMigrations() {}

    public static Map<String, Object> migrate(DatabaseConnection connection, String path) {
        MigrationCoordinator.MigrationResult result = MigrationCoordinator.migrate(backend(connection), path);
        Map<String, Object> value = RuntimeCollections.newMap();
        value.put("applied", result.applied());
        value.put("skipped", result.skipped());
        return value;
    }

    public static List<Object> status(DatabaseConnection connection, String path) {
        List<Object> result = RuntimeCollections.newList();
        for (MigrationCoordinator.MigrationStatus status
                : MigrationCoordinator.status(backend(connection), path)) {
            Map<String, Object> value = RuntimeCollections.newMap();
            value.put("version", status.version());
            value.put("name", status.name());
            value.put("checksum", status.checksum());
            value.put("state", status.state());
            result.add(value);
        }
        return result;
    }

    private static MigrationBackend backend(DatabaseConnection connection) {
        if (!(connection instanceof MigrationCapableConnection capable)) {
            throw new DatabaseFailure("Database provider does not support migrations.");
        }
        if (connection.isClosed()) throw new DatabaseFailure("Connection is closed.");
        return capable.migrationBackend();
    }
}
