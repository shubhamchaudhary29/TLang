package dev.tlang.runtime.database;

/** Provider-neutral database handle exposed by the native db module. */
public interface DatabaseConnection extends DatabaseSession, AutoCloseable {
    DatabaseTransaction begin();
    String providerName();

    @Override
    void close();
}
