package dev.tlang.runtime.database;

import java.util.List;

/** Small provider hook used by the provider-neutral migration coordinator. */
interface MigrationBackend {
    SqlScriptParser.Dialect dialect();

    <T> T withMigrationLock(MigrationWork<T> work);

    @FunctionalInterface
    interface MigrationWork<T> {
        T run(MigrationStore store);
    }

    interface MigrationStore {
        List<MigrationHistoryEntry> history();
        void apply(MigrationFile migration, String appliedAt);
    }
}
