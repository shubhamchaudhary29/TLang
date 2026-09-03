package dev.tlang.runtime.database;

/** One validated row from TLang's database migration history. */
record MigrationHistoryEntry(int version, String name, String checksum, String appliedAt) {
}
