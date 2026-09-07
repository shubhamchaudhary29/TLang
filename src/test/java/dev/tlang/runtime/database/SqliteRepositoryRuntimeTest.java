package dev.tlang.runtime.database;

final class SqliteRepositoryRuntimeTest extends RepositoryRuntimeContract {
    @Override protected String connectionExpression() { return "db.open(\":memory:\")"; }
}
