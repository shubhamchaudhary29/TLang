package dev.tlang.runtime.database;

/** Internal extension implemented by M1 database handles. */
interface MigrationCapableConnection extends DatabaseConnection {
    MigrationBackend migrationBackend();
}
