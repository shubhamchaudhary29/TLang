package dev.tlang.runtime.database;

/** Creates database handles for one target family. */
public interface DatabaseProvider {
    boolean accepts(String target);
    DatabaseConnection open(DatabaseOptions options);
}
