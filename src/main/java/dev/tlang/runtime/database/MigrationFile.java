package dev.tlang.runtime.database;

import java.nio.file.Path;
import java.util.List;

/** Immutable snapshot of one validated migration file. */
record MigrationFile(
        int version,
        String versionText,
        String name,
        String filename,
        Path path,
        String checksum,
        List<String> statements) {
}
