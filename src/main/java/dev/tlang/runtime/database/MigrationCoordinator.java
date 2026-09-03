package dev.tlang.runtime.database;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Provider-neutral forward-only migration validation and orchestration. */
final class MigrationCoordinator {
    private static final Pattern CHECKSUM = Pattern.compile("^[0-9a-f]{64}$");

    private MigrationCoordinator() {}

    static MigrationResult migrate(MigrationBackend backend, String path) {
        List<MigrationFile> migrations = MigrationDiscovery.discover(path, backend.dialect());
        return backend.withMigrationLock(store -> {
            History history = validate(store.history());
            int applied = 0;
            int skipped = 0;
            for (MigrationFile migration : migrations) {
                MigrationHistoryEntry existing = history.byVersion.get(migration.version());
                if (existing != null) {
                    verifyIdentity(migration, existing);
                    skipped++;
                    continue;
                }
                if (migration.version() < history.frontier) {
                    throw new DatabaseFailure("Migration " + migration.filename()
                        + " is out of order; migration history is append-only.");
                }
                store.apply(migration, Instant.now().toString());
                history.byVersion.put(migration.version(), new MigrationHistoryEntry(
                    migration.version(), migration.name(), migration.checksum(), Instant.EPOCH.toString()));
                history.frontier = Math.max(history.frontier, migration.version());
                applied++;
            }
            return new MigrationResult(applied, skipped);
        });
    }

    static List<MigrationStatus> status(MigrationBackend backend, String path) {
        List<MigrationFile> migrations = MigrationDiscovery.discover(path, backend.dialect());
        return backend.withMigrationLock(store -> {
            History history = validate(store.history());
            List<MigrationStatus> result = new ArrayList<>();
            for (MigrationFile migration : migrations) {
                MigrationHistoryEntry existing = history.byVersion.get(migration.version());
                if (existing != null) {
                    verifyIdentity(migration, existing);
                    result.add(new MigrationStatus(
                        migration.version(), migration.name(), migration.checksum(), "applied"));
                } else {
                    if (migration.version() < history.frontier) {
                        throw new DatabaseFailure("Migration " + migration.filename()
                            + " is out of order; migration history is append-only.");
                    }
                    result.add(new MigrationStatus(
                        migration.version(), migration.name(), migration.checksum(), "pending"));
                }
            }
            return List.copyOf(result);
        });
    }

    private static History validate(List<MigrationHistoryEntry> entries) {
        Map<Integer, MigrationHistoryEntry> byVersion = new HashMap<>();
        Set<String> identities = new HashSet<>();
        int frontier = 0;
        for (MigrationHistoryEntry entry : entries) {
            if (entry.version() <= 0 || !MigrationDiscovery.validName(entry.name())
                    || entry.checksum() == null || !CHECKSUM.matcher(entry.checksum()).matches()
                    || entry.appliedAt() == null || !validTimestamp(entry.appliedAt())
                    || byVersion.putIfAbsent(entry.version(), entry) != null
                    || !identities.add(entry.version() + "\u0000" + entry.name())) {
                throw new DatabaseFailure("Migration history is invalid.");
            }
            frontier = Math.max(frontier, entry.version());
        }
        return new History(byVersion, frontier);
    }

    private static boolean validTimestamp(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException failure) {
            return false;
        }
    }

    private static void verifyIdentity(MigrationFile migration, MigrationHistoryEntry existing) {
        if (!migration.name().equals(existing.name())) {
            throw new DatabaseFailure("Migration " + migration.version()
                + " has a conflicting migration name.");
        }
        if (!migration.checksum().equals(existing.checksum())) {
            throw new DatabaseFailure(
                "Migration " + migration.filename() + " has changed after being applied.");
        }
    }

    record MigrationResult(int applied, int skipped) {}
    record MigrationStatus(int version, String name, String checksum, String state) {}

    private static final class History {
        private final Map<Integer, MigrationHistoryEntry> byVersion;
        private int frontier;

        private History(Map<Integer, MigrationHistoryEntry> byVersion, int frontier) {
            this.byVersion = byVersion;
            this.frontier = frontier;
        }
    }
}
